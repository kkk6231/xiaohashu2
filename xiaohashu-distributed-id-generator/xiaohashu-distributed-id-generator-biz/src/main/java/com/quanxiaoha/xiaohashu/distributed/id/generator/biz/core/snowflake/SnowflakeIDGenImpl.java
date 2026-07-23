package com.quanxiaoha.xiaohashu.distributed.id.generator.biz.core.snowflake;

import com.google.common.base.Preconditions;
import com.quanxiaoha.xiaohashu.distributed.id.generator.biz.core.IDGen;
import com.quanxiaoha.xiaohashu.distributed.id.generator.biz.core.common.Result;
import com.quanxiaoha.xiaohashu.distributed.id.generator.biz.core.common.Status;
import com.quanxiaoha.xiaohashu.distributed.id.generator.biz.core.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

public class SnowflakeIDGenImpl implements IDGen {

    @Override
    public boolean init() {
        return true;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SnowflakeIDGenImpl.class);

    private final long twepoch;
    private final long workerIdBits = 10L;
    private final long maxWorkerId = ~(-1L << workerIdBits);//最大能够分配的workerid =1023
    private final long sequenceBits = 12L;
    private final long workerIdShift = sequenceBits;
    private final long timestampLeftShift = sequenceBits + workerIdBits;
    private final long sequenceMask = ~(-1L << sequenceBits);
    private long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;
    private static final Random RANDOM = new Random();

    public SnowflakeIDGenImpl(String zkAddress, int port) {
        //Thu Nov 04 2010 09:42:54 GMT+0800 (中国标准时间) 
        this(zkAddress, port, 1288834974657L);
    }

    /**
     * @param zkAddress zk地址
     * @param port      snowflake监听端口
     * @param twepoch   起始的时间戳
     */
    public SnowflakeIDGenImpl(String zkAddress, int port, long twepoch) {
        this.twepoch = twepoch;
        Preconditions.checkArgument(timeGen() > twepoch, "Snowflake not support twepoch gt currentTime");
        final String ip = Utils.getIp();
        SnowflakeZookeeperHolder holder = new SnowflakeZookeeperHolder(ip, String.valueOf(port), zkAddress);
        LOGGER.info("twepoch:{} ,ip:{} ,zkAddress:{} port:{}", twepoch, ip, zkAddress, port);
        boolean initFlag = holder.init();
        if (initFlag) {
            workerId = holder.getWorkerID();
            LOGGER.info("START SUCCESS USE ZK WORKERID-{}", workerId);
        } else {
            Preconditions.checkArgument(initFlag, "Snowflake Id Gen is not init ok");
        }
        Preconditions.checkArgument(workerId >= 0 && workerId <= maxWorkerId, "workerID must gte 0 and lte 1023");
    }

    @Override
    public synchronized Result get(String key) {  // get 方法加 synchronized 是因为 sequence 和 lastTimestamp 不是线程安全的
        long timestamp = timeGen();
        if (timestamp < lastTimestamp) {  // 发生时钟回拨
            long offset = lastTimestamp - timestamp;
            if (offset <= 5) {  // 5ms 内等
                try {
                    wait(offset << 1);
                    timestamp = timeGen();
                    if (timestamp < lastTimestamp) {
                        return new Result(-1, Status.EXCEPTION);
                    }
                } catch (InterruptedException e) {
                    LOGGER.error("wait interrupted");
                    return new Result(-2, Status.EXCEPTION);
                }
            } else {
                return new Result(-3, Status.EXCEPTION);  // 大于 5 ms 直接报错
            }
        }
        if (lastTimestamp == timestamp) {  // 如果是同一毫秒，序列号加 1
            sequence = (sequence + 1) & sequenceMask;
            if (sequence == 0) {  // 当前毫秒的序列号用完了
                //seq 为 0 的时候表示是下一毫秒时间开始对 seq 做随机
                sequence = RANDOM.nextInt(100);  // 随机初始化防止 ID 被猜测，增加安全性
                timestamp = tilNextMillis(lastTimestamp);  // 阻塞直到时间变化
            }
        } else {
            //如果是新的ms开始
            sequence = RANDOM.nextInt(100);
        }
        lastTimestamp = timestamp;
        long id = ((timestamp - twepoch) << timestampLeftShift) | (workerId << workerIdShift) | sequence;
        return new Result(id, Status.SUCCESS);

    }

    protected long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    protected long timeGen() {
        return System.currentTimeMillis();
    }

    public long getWorkerId() {
        return workerId;
    }

}
