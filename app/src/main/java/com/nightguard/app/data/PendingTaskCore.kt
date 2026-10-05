package com.nightguard.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 进程内共享实例：NLS / Receiver / Service / UI 所有入口共用同一个状态机，
 * 保证跨入口的状态转换都经过同一把互斥锁。
 */
object PendingTasks : PendingTaskCore(Store.pendingTaskBacking)

/** 待响任务状态 */
object TaskStatus {
    const val ARMED = "armed"      // 已装载，等待到期
    const val FAILED = "failed"    // 装载失败（调度异常），保留供诊断
    const val EXPIRED = "expired"  // 到期但被跳过（重启恢复时已超时），保留供诊断
}

/**
 * 待响任务：一次“延迟响铃”从装载到执行/取消的唯一实体。
 * 兼容旧版 `pendingAlarm`（Map<ruleId,毫秒>）：迁移时 taskId 用 `legacy-<ruleId>`。
 */
data class PendingTask(
    val taskId: String,
    val ruleId: String,
    /** 到期时间戳（毫秒） */
    val fireAt: Long,
    /** 触发来源（如“消息·shell”），仅用于日志 */
    val source: String,
    val createdAt: Long,
    /** 来电触发：到期前监听接听事件自动取消 */
    val fromCall: Boolean = false,
    val status: String = TaskStatus.ARMED,
) {
    companion object {
        const val LEGACY_PREFIX = "legacy-"
    }
}

/**
 * 状态机上下文占位：生产实现传 android.content.Context，
 * 纯 JVM 测试传 Unit——核心逻辑不依赖 Android 类型。
 */
typealias TaskCtx = Any

/** 任务状态仓库抽象：生产实现走 DataStore（含旧数据迁移），测试用内存实现 */
interface PendingTaskBacking {
    suspend fun read(ctx: TaskCtx): List<PendingTask>
    suspend fun write(ctx: TaskCtx, tasks: List<PendingTask>)
}

/**
 * 待响任务状态机（纯逻辑，无 Android 依赖）。
 * 所有 check-then-act 都在同一把互斥锁内完成，保证：
 * - 同一规则并发装载只产生一个 armed 任务（其余为去重跳过）；
 * - 到期广播必须携带当前任务的 taskId 才有效——旧广播不能触发新任务、
 *   清除新任务或复活已取消任务；
 * - 启动/重启/更新后的恢复（[recover]）幂等：过期任务明确跳过，
 *   已禁用/已删除规则的任务不恢复。
 */
open class PendingTaskCore(    private val backing: PendingTaskBacking,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { "t-" + java.util.UUID.randomUUID() },
    /** 重启恢复时，到期不超过该宽限期的任务补响（catch-up），更久的视为过期跳过 */
    private val graceMs: Long = 60_000L,
    private val catchUpDelayMs: Long = 15_000L,
    /** 失败/过期记录保留的诊断窗口，超时清理 */
    private val diagnosticKeepMs: Long = 24 * 3600_000L,
    private val diagnosticKeepCount: Int = 20,
) {
    private val mutex = Mutex()

    sealed interface ArmDecision {
        /** 写入新任务；调用方随后排闹钟，失败时须调 [markFailed] */
        data class Write(val task: PendingTask, val replaced: PendingTask?) : ArmDecision
        /** 去重跳过：该规则已有未到期任务（不得记作装载成功） */
        data class Deduped(val existing: PendingTask) : ArmDecision
    }

    /** 装载任务。同规则已有未到期 armed 任务则去重跳过；旧任务已到期则替换 */
    suspend fun arm(ctx: TaskCtx, ruleId: String, at: Long, source: String, fromCall: Boolean): ArmDecision =
        mutex.withLock {
            val tasks = backing.read(ctx).toMutableList()
            val n = now()
            val idx = tasks.indexOfFirst { it.ruleId == ruleId && it.status == TaskStatus.ARMED }
            val existing = if (idx >= 0) tasks[idx] else null
            if (existing != null && existing.fireAt > n) return ArmDecision.Deduped(existing)
            val task = PendingTask(
                taskId = newId(), ruleId = ruleId, fireAt = at,
                source = source, createdAt = n, fromCall = fromCall,
            )
            if (idx >= 0) tasks[idx] = task else tasks.add(task)
            backing.write(ctx, tasks)
            ArmDecision.Write(task, existing)
        }

    sealed interface CancelDecision {
        data class Cancelled(val task: PendingTask) : CancelDecision
        /** taskId 不匹配：请求来自过期通知，忽略（新任务保持不变） */
        data object StaleRequest : CancelDecision
        /** 当前没有待响任务 */
        data object NotFound : CancelDecision
    }

    /**
     * 取消规则的当前任务。
     * @param taskId 携带则校验（“本次不响”通知上的精确取消）；null 为规则级取消
     *   （禁用/删除/抑制等意图，作用对象就是当前任务，不校验）。
     */
    suspend fun cancel(ctx: TaskCtx, ruleId: String, taskId: String?): CancelDecision = mutex.withLock {
        val tasks = backing.read(ctx).toMutableList()
        val idx = tasks.indexOfFirst { it.ruleId == ruleId && it.status == TaskStatus.ARMED }
        if (idx < 0) return CancelDecision.NotFound
        val t = tasks[idx]
        if (taskId != null && taskId != t.taskId) return CancelDecision.StaleRequest
        tasks.removeAt(idx)
        backing.write(ctx, tasks)
        CancelDecision.Cancelled(t)
    }

    /**
     * 到期广播验收：taskId 与当前 armed 任务一致才放行并移除记录。
     * taskId 为 null 的旧格式广播只接受迁移出来的 legacy 任务，避免更新后
     * 残留的旧闹钟广播误触发新任务。
     */
    suspend fun consumeForFire(ctx: TaskCtx, ruleId: String, taskId: String?): PendingTask? = mutex.withLock {
        val tasks = backing.read(ctx).toMutableList()
        val idx = tasks.indexOfFirst { it.ruleId == ruleId && it.status == TaskStatus.ARMED }
        if (idx < 0) return null
        val t = tasks[idx]
        val valid = if (taskId != null) taskId == t.taskId else t.taskId.startsWith(PendingTask.LEGACY_PREFIX)
        if (!valid) return null
        tasks.removeAt(idx)
        backing.write(ctx, tasks)
        t
    }

    /** 调度失败：任务标记为 failed（保留诊断），不再可触发、不再参与去重 */
    suspend fun markFailed(ctx: TaskCtx, ruleId: String, taskId: String): Boolean = mutex.withLock {
        val tasks = backing.read(ctx).toMutableList()
        val idx = tasks.indexOfFirst {
            it.taskId == taskId && it.ruleId == ruleId && it.status == TaskStatus.ARMED
        }
        if (idx < 0) return false
        tasks[idx] = tasks[idx].copy(status = TaskStatus.FAILED)
        backing.write(ctx, tasks)
        true
    }

    suspend fun taskForRule(ctx: TaskCtx, ruleId: String): PendingTask? = mutex.withLock {
        backing.read(ctx).firstOrNull { it.ruleId == ruleId && it.status == TaskStatus.ARMED }
    }

    data class RecoveryPlan(
        /** 未来时刻任务：重排闹钟（幂等，重复 set 同一 PI 无害） */
        val rearm: List<PendingTask>,
        /** 宽限期内到期：改期到 now+catchUpDelayMs 补响 */
        val catchUp: List<PendingTask>,
        /** 过期跳过：不响，记录转 expired 保留诊断 */
        val expired: List<PendingTask>,
        /** 规则已删除/已禁用/无响铃动作：丢弃 */
        val dropped: List<Pair<PendingTask, String>>,
    )

    /**
     * 启动/重启/应用更新后的幂等恢复。精确闹钟重启会丢，这里按任务记录重排；
     * 已禁用或已删除规则的任务不恢复；过期超过宽限期的任务明确跳过。
     */
    suspend fun recover(ctx: TaskCtx, rules: List<Rule>): RecoveryPlan = mutex.withLock {
        val n = now()
        val ruleById = rules.associateBy { it.id }
        val rearm = mutableListOf<PendingTask>()
        val catchUp = mutableListOf<PendingTask>()
        val expired = mutableListOf<PendingTask>()
        val dropped = mutableListOf<Pair<PendingTask, String>>()
        val diagnostics = mutableListOf<PendingTask>()

        for (t in backing.read(ctx)) {
            when (t.status) {
                TaskStatus.ARMED -> {
                    val r = ruleById[t.ruleId]
                    when {
                        r == null -> dropped += t to "规则已删除"
                        !r.enabled -> dropped += t to "规则已禁用"
                        !r.delayedAlarm -> dropped += t to "规则无响铃动作"
                        t.fireAt <= n - graceMs -> expired += t
                        t.fireAt <= n -> catchUp += t.copy(fireAt = n + catchUpDelayMs)
                        else -> rearm += t
                    }
                }
                else -> if (n - t.createdAt <= diagnosticKeepMs) diagnostics += t
            }
        }
        val keepDiagnostics = (diagnostics + expired.map { it.copy(status = TaskStatus.EXPIRED) })
            .takeLast(diagnosticKeepCount)
        backing.write(ctx, rearm + catchUp + keepDiagnostics)
        RecoveryPlan(rearm, catchUp, expired, dropped)
    }
}
