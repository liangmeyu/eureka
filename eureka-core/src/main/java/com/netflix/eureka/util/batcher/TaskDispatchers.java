package com.netflix.eureka.util.batcher;

/**
 * See {@link TaskDispatcher} for an overview.
 *
 * @author Tomasz Bak
 */
public class TaskDispatchers {

    public static <ID, T> TaskDispatcher<ID, T> createNonBatchingTaskDispatcher(String id,
                                                                                // 10000
                                                                                int maxBufferSize,
                                                                                int workerCount,
                                                                                long maxBatchingDelay,
                                                                                long congestionRetryDelayMs,
                                                                                long networkFailureRetryMs,
                                                                                TaskProcessor<T> taskProcessor) {
        final AcceptorExecutor<ID, T> acceptorExecutor = new AcceptorExecutor<>(
                id,
                // 10000
                maxBufferSize, 1, maxBatchingDelay, congestionRetryDelayMs,
                networkFailureRetryMs
        );
        final TaskExecutors<ID, T> taskExecutor = TaskExecutors.singleItemExecutors(id,
                workerCount, taskProcessor, acceptorExecutor);
        return new TaskDispatcher<ID, T>() {
            @Override
            public void process(ID id, T task, long expiryTime) {
                acceptorExecutor.process(id, task, expiryTime);
            }

            @Override
            public void shutdown() {
                acceptorExecutor.shutdown();
                taskExecutor.shutdown();
            }
        };
    }

    /**
     * 创建批量任务调度器
     *
     * @param id
     * @param maxBufferSize
     * @param workloadSize
     * @param workerCount
     * @param maxBatchingDelay
     * @param congestionRetryDelayMs
     * @param networkFailureRetryMs
     * @param taskProcessor
     * @param <ID>
     * @param <T>
     * @return
     */
    public static <ID, T> TaskDispatcher<ID, T> createBatchingTaskDispatcher(String id,
                                                                             int maxBufferSize,
                                                                             int workloadSize,
                                                                             int workerCount,
                                                                             long maxBatchingDelay,
                                                                             long congestionRetryDelayMs,
                                                                             long networkFailureRetryMs,
                                                                             // 同步任务处理器
                                                                             TaskProcessor<T> taskProcessor) {

        /**
         * Acceptor执行器
         */
        final AcceptorExecutor<ID, T> acceptorExecutor = new AcceptorExecutor<>(
                id, maxBufferSize, workloadSize, maxBatchingDelay, congestionRetryDelayMs,
                networkFailureRetryMs
        );
        /**
         * Task执行器池
         */
        final TaskExecutors<ID, T> taskExecutor = TaskExecutors.batchExecutors(id, workerCount,
                // 同步任务处理器
                taskProcessor,
                // Acceptor处理器
                acceptorExecutor);
        /**
         * 任务分发器
         */
        return new TaskDispatcher<ID, T>() {
            @Override
            public void process(ID id, T task, long expiryTime) {
                acceptorExecutor.process(id, task, expiryTime);
            }

            @Override
            public void shutdown() {
                acceptorExecutor.shutdown();
                taskExecutor.shutdown();
            }
        };
    }
}
