package com.loan.config;

import org.slf4j.MDC;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置（阶段二 2.3）
 *
 * <p><b>修复两个真实问题</b>：
 * <ol>
 *   <li><b>`@Async` 无自定义线程池</b> —— 项目里原本有 1 处 `@Async`（`PaymentServiceImpl`），
 *       但没有 {@code @EnableAsync}、也没有 {@code ThreadPoolTaskExecutor} Bean。
 *       这种写法会退化到 {@code SimpleAsyncTaskExecutor}：<b>每次调用新建线程、无上限、不复用</b>，
 *       高并发下会直接 OOM。</li>
 *   <li><b>异步线程丢失 traceId</b> —— 这是阶段一 1.3 明确记录的已知限制：
 *       MDC 是 ThreadLocal，不会自动传递到新线程。本类用 {@link TaskDecorator} 复制 MDC，
 *       否则异步方法里的日志既没有 traceId 也没有 applicationId，排查时断链。</li>
 * </ol>
 *
 * <p><b>参数依据</b>：
 * <table border="1">
 *   <tr><th>参数</th><th>值</th><th>理由</th></tr>
 *   <tr><td>corePoolSize</td><td>8</td><td>异步任务以 IO 为主（DB/HTTP），约为 CPU 核数 × 2</td></tr>
 *   <tr><td>maxPoolSize</td><td>32</td><td>峰值上限，避免无限扩张拖垮数据库</td></tr>
 *   <tr><td>queueCapacity</td><td>500</td><td>缓冲；太小频繁拒绝，太大掩盖问题</td></tr>
 *   <tr><td>拒绝策略</td><td>CallerRunsPolicy</td><td><b>不用 AbortPolicy</b>：任务不丢，且让调用方变慢形成背压</td></tr>
 * </table>
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /** 异步执行器 Bean 名；{@code @Async("appTaskExecutor")} 需与此一致 */
    public static final String EXECUTOR_NAME = "appTaskExecutor";

    @Bean(EXECUTOR_NAME)
    public ThreadPoolTaskExecutor appTaskExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(8);
        ex.setMaxPoolSize(32);
        ex.setQueueCapacity(500);
        ex.setKeepAliveSeconds(60);
        ex.setThreadNamePrefix("app-async-");
        // 队列满时由调用线程执行 —— 保证任务不丢，同时形成背压
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // ★ 把调用线程的 MDC 复制到异步线程，保证 traceId 不丢
        ex.setTaskDecorator(new MdcTaskDecorator());
        // 优雅停机：等在途任务完成，避免异步任务被硬切断
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }

    /** 若不显式指定，{@code @Async} 默认用这个 */
    @Override
    public Executor getAsyncExecutor() {
        return appTaskExecutor();
    }

    /**
     * 异步方法抛异常时记录日志。
     *
     * <p>不配置的话，{@code void} 返回值的 {@code @Async} 方法抛出的异常<b>会被静默吞掉</b>，
     * 排查时完全看不到。
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }

    /**
     * MDC 透传到异步线程。
     *
     * <p>{@code MDC.getCopyOfContextMap()} 必须在<b>调用线程</b>取（即 decorate 时），
     * 不能在 runnable 内部取 —— 那时已经在异步线程上，MDC 是空的。
     */
    static class MdcTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            Map<String, String> ctx = MDC.getCopyOfContextMap();   // 在调用线程取值
            return () -> {
                if (ctx != null) {
                    MDC.setContextMap(ctx);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        }
    }
}
