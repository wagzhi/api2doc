package com.terran4j.commons.hedis.dschedule;

import com.terran4j.commons.hedis.cache.CacheService;
import com.terran4j.commons.util.Classes;
import com.terran4j.commons.util.DateTimes;
import com.terran4j.commons.util.Strings;
import com.terran4j.commons.util.error.BusinessException;
import com.terran4j.commons.util.error.ErrorCodes;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.EmbeddedValueResolver;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronSequenceGenerator;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

import javax.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Aspect
@Component
public class DSchedulingAspect {

    private static final String instanceId = UUID.randomUUID().toString();

    private static final Map<String, Method> methods = new ConcurrentHashMap<>();

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @Autowired
    private CacheService cacheService;

    private EmbeddedValueResolver embeddedValueResolver;

    @PostConstruct
    public void init() {
        this.embeddedValueResolver = new EmbeddedValueResolver(
                applicationContext.getBeanFactory());
    }

    @Pointcut("@annotation(com.terran4j.commons.hedis.dschedule.DScheduling)")
    public void distributedScheduling() {
    }

    @Around("distributedScheduling()")
    public Object doDistributedScheduling(ProceedingJoinPoint point) throws Throwable {

        // JobExeInfo 用于记录任务信息。
        JobExeInfo jobExeInfo = new JobExeInfo();
        long beginTime = System.currentTimeMillis();
        jobExeInfo.setBeginTime(beginTime);
        jobExeInfo.setInstanceId(instanceId);

        Object targetObject = point.getTarget();
        Class<?> targetClass = Classes.getTargetClass(targetObject);
        String className = targetClass.getName();
        String methodName = point.getSignature().getName();
        jobExeInfo.setClassName(className);
        jobExeInfo.setMethodName(methodName);
        final Logger log = LoggerFactory.getLogger(targetClass);

        // 只有用 @DScheduling 修饰的方法，才会使用并发控制。
        Object[] args = point.getArgs();
        Method method = Classes.getMethod(targetClass, methodName,
                args, DScheduling.class);
        if (method == null) {
            log.error("method not found, className = {}, methodName = {}",
                    className, methodName);
            return point.proceed(args);
        }
        DScheduling distributedScheduling = method.getAnnotation(DScheduling.class);
        if (distributedScheduling == null) {
            log.error("@DistributedScheduling not found, className = {}, methodName = {}", className, methodName);
            return point.proceed(args);
        }

        // 还得要 @Scheduled 修饰。
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        if (scheduled == null) {
            String msg = String.format("${className}.${methodName}方法" +
                            "上有 %s 注解但没有 %s 注解。", DScheduling.class,
                    Scheduled.class);
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR).put("className", className)
                    .put("methodName", methodName).setMessage(msg);
        }

        // 被加上 Scheduled 注解的方法，都是没有参数的，
        // 因此可以用“类名 + 方法名”唯一标识一个任务。
        // 但是“类名 + 方法名”很长，有的类是动态类，类名不一定一样，
        // 应用方需要自己设置distributedScheduling 的唯一标识。
        String value = distributedScheduling.value();
        checkValue(value, method);
        if (log.isInfoEnabled()) {
            log.info("start to DistributedScheduling '{}' at: {}", value, DateTimes.toString(new Date()));
        }

        // 尝试从 redis 中获取分布式锁，如果没有获取到锁，就不执行本次调度。
        String jobInfoKey = "hedis.schedule.jobInfo-" + value;
        String lockKey = "hedis.schedule.lock-" + value;
        long lockExpired = distributedScheduling.lockExpiredSecond() * 1000;
        long jobInfoExpired = lockExpired * 2; // 任务信息保存时间要比锁长，目前设置为 2 倍。
        boolean locked = cacheService.setObjectIfAbsent(lockKey, instanceId, lockExpired);
        if (!locked) {
            // 没有取到锁，不执行任务。
            if (log.isInfoEnabled()) {
                JobExeInfo lastInfo = cacheService.getObject(jobInfoKey, JobExeInfo.class);
                log.info("job is executing by other instance, lastInfo:\n{}", lastInfo);
            }
            return null;
        } else {
            if (log.isInfoEnabled()) {
                log.info("get the lock by instance: {}", instanceId);
            }
        }

        // 查看上次的执行时间，如果没到这次的执行时间，也应该避免。
        JobExeInfo lastInfo = cacheService.getObject(jobInfoKey, JobExeInfo.class);
        if (lastInfo != null) {
            if (!isValidTime(lastInfo, scheduled, distributedScheduling, log)) {
                if (log.isInfoEnabled()) {
                    log.info("job is executed by other instance, but next " +
                            "executing not coming, lastInfo: \n{}", lastInfo);
                }
                cacheService.remove(lockKey); // 释放锁。
                return null;
            }
        }

        Object result = null;
        try {
            if (log.isInfoEnabled()) {
                log.info("now begin to executing the job.");
            }
            // 执行任务之前，先写入任务的执行信息。
            jobExeInfo.setRunning(true);
            cacheService.setObject(jobInfoKey, jobExeInfo, jobInfoExpired);

            // 真正执行任务。
            result = point.proceed(args);
            if (log.isInfoEnabled()) {
                log.info("execute job done, args: {}, result: {}", args, result);
            }

            jobExeInfo.setResultCode("SUCCESS");
            if (log.isInfoEnabled()) {
                log.info("executing the job done.");
            }
        } catch (BusinessException e) {
            jobExeInfo.setResultCode(e.getErrorCode().getName());
            jobExeInfo.setMessage(e.getMessage());
            if (log.isInfoEnabled()) {
                log.info("executing the job error: {}", e.getMessage());
            }
        } catch (Throwable e) {
            jobExeInfo.setResultCode(ErrorCodes.UNKNOWN_ERROR);
            jobExeInfo.setMessage(e.getMessage());
            if (log.isInfoEnabled()) {
                log.info("executing the job error: {}", e.getMessage());
            }
        } finally {
            // 保存本次任务的执行信息。
            long endTime = System.currentTimeMillis();
            jobExeInfo.setEndTime(endTime);
            jobExeInfo.setRunning(false);
            cacheService.setObject(jobInfoKey, jobExeInfo, jobInfoExpired);
            if (log.isInfoEnabled()) {
                log.info("done DScheduling, jobExeInfo:\n{}", jobExeInfo);
            }

            // 释放锁。
            cacheService.remove(lockKey);
        }

        if (log.isInfoEnabled()) {
            log.info("end of DistributedScheduling '{}' at: {}", value,
                    DateTimes.toString(new Date()));
        }
        return result;
    }

    /**
     * 服务器之间可容忍的时间差距，以毫秒为单位。<br>
     * 你要尽量保证分布式系统之间的时钟是一致的（这个业界有很多种办法）。<br>
     * 在保证了时钟一致的情况下，这个值可以尽量的小。<br>
     *
     * @return
     */
    long tolerableTimeDeviation() {
        return 10;
    }


    Long getMinPoint(Long a, long b) {
        if (a == null) {
            return b;
        }
        return a < b ? a : b;
    }

    boolean isValidTime(JobExeInfo lastInfo, Scheduled scheduled,
                        DScheduling distributedScheduling, Logger log) {
        // 之前没有任务执行，视为有效的执行时间。
        if (lastInfo == null) {
            return true;
        }

        // 无效的任务信息，视为无效的执行时间。
        if (lastInfo.getBeginTime() == null || lastInfo.getEndTime() == null) {
            if (log.isInfoEnabled()) {
                log.info("Invalid lastInfo(beginTime OR endTime is null): {}", lastInfo);
            }
            return false;
        }

        long currentTime = System.currentTimeMillis();
        long lastBeginTime = lastInfo.getBeginTime();
        long lastEndTime = lastInfo.getEndTime();
        long tolerableTime = tolerableTimeDeviation();
        StringValueResolver resolver = getResolver();
        Long point = null; // 最早开始的时间点。

        String cornText = scheduled.cron();
        if (StringUtils.hasText(cornText)) {
            cornText = resolver.resolveStringValue(cornText);

            String zone = scheduled.zone();
            TimeZone timeZone;
            if (StringUtils.hasText(zone)) {
                zone = resolver.resolveStringValue(zone);
                timeZone = StringUtils.parseTimeZoneString(zone);
            } else {
                timeZone = TimeZone.getDefault();
            }
            CronSequenceGenerator corn = new CronSequenceGenerator(cornText, timeZone);
            Long currentPoint = corn.next(new Date(lastEndTime)).getTime();
            point = getMinPoint(point, currentPoint);
        }

        long fixedDelay = scheduled.fixedDelay();
        if (fixedDelay >= 0) {
            Long currentPoint = lastEndTime + fixedDelay;
            point = getMinPoint(point, currentPoint);
        }

        String fixedDelayString = scheduled.fixedDelayString();
        if (StringUtils.hasText(fixedDelayString)) {
            fixedDelayString = resolver.resolveStringValue(fixedDelayString);
            Long currentPoint = lastEndTime + Long.parseLong(fixedDelayString);
            point = getMinPoint(point, currentPoint);
        }

        long fixedRate = scheduled.fixedRate();
        if (fixedRate > 0) {
            Long currentPoint = lastBeginTime + fixedRate;
            point = getMinPoint(point, currentPoint);
        }

        String fixedRateString = scheduled.fixedRateString();
        if (StringUtils.hasText(fixedRateString)) {
            fixedRateString = resolver.resolveStringValue(fixedRateString);
            Long currentPoint = lastBeginTime + fixedRate;
            point = getMinPoint(point, currentPoint);
        }

        Assert.isTrue(point != null, "Invalid scheduled: " + scheduled);

        return point - tolerableTime <= currentTime;
    }

    void checkValue(String value, Method method) throws BusinessException {
        if (method == null) {
            throw new NullPointerException("method is null.");
        }
        if (StringUtils.isEmpty(value)) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR)
                    .put("value", value)
                    .put("className", method.getDeclaringClass().getName())
                    .put("methodName", method.getName())
                    .setMessage("@DistributedScheduling value can't be empty.");
        }
        boolean isDuplicate = false;
        Method existedMethod = methods.get(value);
        if (existedMethod == null) {
            synchronized (this) {
                existedMethod = methods.get(value);
                if (existedMethod != null && existedMethod != method) {
                    isDuplicate = true;
                } else if (existedMethod == null) {
                    methods.put(value, method);
                }
            }
        } else if (isDuplicate || !existedMethod.equals(method)) {
            String msg = "@DistributedScheduling(\"${value}\") is duplicated with another.";
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR)
                    .put("value", value)
                    .put("className", method.getDeclaringClass().getName())
                    .put("methodName", method.getName())
                    .put("existedClassName", existedMethod.getDeclaringClass().getName())
                    .put("existedMethodName", existedMethod.getName())
                    .setMessage(msg);
        }
    }

    StringValueResolver getResolver() {
        return this.embeddedValueResolver;
    }
}
