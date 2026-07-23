# 小哈书计数服务实现说明

## 模块定位

计数服务位于 `xiaohashu-count`，核心实现集中在 `xiaohashu-count-biz`。它负责维护平台中的各种计数数据，例如：

- 用户维度：关注数、粉丝数、笔记发布数、获赞数、获收藏数
- 笔记维度：点赞数、收藏数、评论数
- 评论维度：评论点赞数、子评论数

计数服务不直接承接用户请求里的点赞、关注、评论等写操作，而是通过 RocketMQ 消费其他业务服务产生的行为消息，再异步更新 Redis 和 MySQL。这样可以把高频写操作从核心业务接口中拆出来，降低接口响应耗时，并通过聚合、削峰保护数据库。

## 核心目录

```text
xiaohashu-count
├── xiaohashu-count-biz
│   ├── config
│   │   ├── RedisTemplateConfig.java
│   │   └── RocketMQConfig.java
│   ├── constant
│   │   ├── MQConstants.java
│   │   └── RedisKeyConstants.java
│   ├── consumer
│   │   ├── CountFansConsumer.java
│   │   ├── CountFans2DBConsumer.java
│   │   ├── CountFollowingConsumer.java
│   │   ├── CountFollowing2DBConsumer.java
│   │   ├── CountNoteLikeConsumer.java
│   │   ├── CountNoteLike2DBConsumer.java
│   │   ├── CountNoteCollectConsumer.java
│   │   ├── CountNoteCollect2DBConsumer.java
│   │   ├── CountNoteCommentConsumer.java
│   │   ├── CountNoteChildCommentConsumer.java
│   │   ├── CountNotePublishConsumer.java
│   │   ├── CountCommentLikeConsumer.java
│   │   └── CountCommentLike2DBConsumer.java
│   ├── domain
│   │   ├── dataobject
│   │   └── mapper
│   ├── enums
│   └── model/dto
└── xiaohashu-count-api
```

## 数据模型

### 用户计数表

Mapper 文件：`xiaohashu-count-biz/src/main/resources/mapper/UserCountDOMapper.xml`

对应表：`t_user_count`

主要字段：

| 字段 | 含义 |
| --- | --- |
| `user_id` | 用户 ID |
| `fans_total` | 粉丝数 |
| `following_total` | 关注数 |
| `note_total` | 发布笔记数 |
| `like_total` | 获赞数 |
| `collect_total` | 获收藏数 |

落库方式主要使用 `INSERT ... ON DUPLICATE KEY UPDATE`：

```sql
INSERT INTO t_user_count (user_id, fans_total)
VALUES (#{userId}, #{count})
ON DUPLICATE KEY UPDATE fans_total = fans_total + (#{count});
```

这类写法的意义是：如果用户计数记录不存在就插入，存在就累加，避免业务层先查再改。

### 笔记计数表

Mapper 文件：`xiaohashu-count-biz/src/main/resources/mapper/NoteCountDOMapper.xml`

对应表：`t_note_count`

主要字段：

| 字段 | 含义 |
| --- | --- |
| `note_id` | 笔记 ID |
| `like_total` | 点赞数 |
| `collect_total` | 收藏数 |
| `comment_total` | 评论数 |

同样使用 `INSERT ... ON DUPLICATE KEY UPDATE` 对点赞、收藏、评论进行累加更新。

### 评论计数表

Mapper 文件：`xiaohashu-count-biz/src/main/resources/mapper/CommentDOMapper.xml`

主要用于评论点赞数、子评论数等评论维度计数。

## Redis 计数 Key

常量文件：`RedisKeyConstants.java`

计数服务使用 Redis Hash 保存各对象维度的计数缓存。

| Redis Key | 维度 | 构造方法 |
| --- | --- | --- |
| `count:user:{userId}` | 用户计数 | `buildCountUserKey` |
| `count:note:{noteId}` | 笔记计数 | `buildCountNoteKey` |
| `count:comment:{commentId}` | 评论计数 | `buildCountCommentKey` |

Hash Field：

| Field | 含义 |
| --- | --- |
| `likeTotal` | 点赞数 |
| `noteTotal` | 发布笔记数 |
| `collectTotal` | 收藏数 |
| `commentTotal` | 评论数 |
| `fansTotal` | 粉丝数 |
| `followingTotal` | 关注数 |
| `childCommentTotal` | 子评论数 |

代码里更新 Redis 前通常会先判断 Key 是否存在：

```java
boolean isExisted = redisTemplate.hasKey(redisKey);
if (isExisted) {
    redisTemplate.opsForHash().increment(redisKey, field, count);
}
```

这样做的原因是：计数缓存可能设置了过期时间，如果缓存不存在，计数服务不负责初始化缓存，避免只写出一个不完整 Hash。缓存初始化通常应该放在查询计数时完成。

## MQ Topic

常量文件：`MQConstants.java`

| Topic | 用途 | 消费者 |
| --- | --- | --- |
| `CountFollowingTopic` | 关注数计数 | `CountFollowingConsumer` |
| `CountFollowing2DBTopic` | 关注数落库 | `CountFollowing2DBConsumer` |
| `CountFansTopic` | 粉丝数计数 | `CountFansConsumer` |
| `CountFans2DBTopic` | 粉丝数落库 | `CountFans2DBConsumer` |
| `CountNoteLikeTopic` | 笔记点赞数计数 | `CountNoteLikeConsumer` |
| `CountNoteLike2DBTTopic` | 笔记点赞数落库 | `CountNoteLike2DBConsumer` |
| `CountNoteCollectTopic` | 笔记收藏数计数 | `CountNoteCollectConsumer` |
| `CountNoteCollect2DBTTopic` | 笔记收藏数落库 | `CountNoteCollect2DBConsumer` |
| `CountNoteCommentTopic` | 笔记评论数计数 | `CountNoteCommentConsumer` |
| `NoteOperateTopic` | 笔记发布/删除计数 | `CountNotePublishConsumer` |
| `CommentLikeUnlikeTopic` | 评论点赞/取消点赞计数 | `CountCommentLikeConsumer` |
| `CountCommentLike2DBTTopic` | 评论点赞数落库 | `CountCommentLike2DBConsumer` |

## 总体写链路

计数服务的典型高并发链路如下：

```text
业务服务产生行为
    ↓
发送计数 MQ
    ↓
计数服务消费 MQ
    ↓
BufferTrigger 聚合消息
    ↓
按业务对象分组，计算净增量
    ↓
更新 Redis Hash
    ↓
发送二级 MQ
    ↓
2DB Consumer 使用 RateLimiter 削峰
    ↓
MySQL 累加落库
```

这个设计把用户请求中的计数写入变成异步流程。用户点赞、收藏、关注时，业务服务只需要完成自己的核心状态写入或缓存更新，然后发 MQ，计数更新可以最终一致。

## 粉丝数计数

入口消费者：`CountFansConsumer.java`

落库消费者：`CountFans2DBConsumer.java`

来源事件：用户关系服务关注/取关后发送计数消息。

处理流程：

1. `CountFansConsumer` 监听 `CountFansTopic`。
2. 收到消息后不立即处理，而是放入 `BufferTrigger`。
3. `BufferTrigger` 配置：

```java
bufferSize(50000)
batchSize(1000)
linger(Duration.ofSeconds(1))
```

含义是最多缓存 50000 条，每批最多聚合 1000 条，或者 1 秒触发一次聚合。

4. 聚合时按 `targetUserId` 分组。
5. 根据操作类型计算净增量：

```text
FOLLOW   -> +1
UNFOLLOW -> -1
```

6. 如果 Redis 中存在 `count:user:{targetUserId}`，则累加 `fansTotal`。
7. 将聚合后的 `Map<userId, count>` 发送到 `CountFans2DBTopic`。
8. `CountFans2DBConsumer` 消费二级 MQ，通过 `RateLimiter.create(5000)` 控制落库速率。
9. 调用 `UserCountDOMapper.insertOrUpdateFansTotalByUserId` 累加写入 `t_user_count`。

学习重点：

- 为什么粉丝数要聚合：一个大 V 被大量关注时，粉丝数变化可能非常高频。
- 为什么按 `targetUserId` 分组：粉丝数变化的是被关注者。
- 为什么先 Redis 后 DB：Redis 承接实时读，DB 做最终持久化。

## 关注数计数

入口消费者：`CountFollowingConsumer.java`

落库消费者：`CountFollowing2DBConsumer.java`

关注数和粉丝数的区别是：关注数变化发生在主动操作用户身上，而且单个用户短时间内不太可能关注海量用户。因此代码里没有使用 `BufferTrigger` 聚合，而是直接处理单条消息。

处理流程：

1. `CountFollowingConsumer` 监听 `CountFollowingTopic`。
2. 解析 `CountFollowUnfollowMqDTO`。
3. 根据操作类型计算：

```text
FOLLOW   -> followingTotal +1
UNFOLLOW -> followingTotal -1
```

4. 如果 Redis 中存在 `count:user:{userId}`，则累加 `followingTotal`。
5. 发送消息到 `CountFollowing2DBTopic`。
6. `CountFollowing2DBConsumer` 消费后落库到 `t_user_count`。

学习重点：

- 粉丝数按被关注者聚合，关注数按操作者更新。
- 关注数没有做 BufferTrigger，是基于业务流量特征做的取舍。

## 笔记点赞数计数

入口消费者：`CountNoteLikeConsumer.java`

落库消费者：`CountNoteLike2DBConsumer.java`

来源事件：笔记服务点赞/取消点赞落库成功后发送 `CountNoteLikeTopic`。

处理流程：

1. `CountNoteLikeConsumer` 监听 `CountNoteLikeTopic`。
2. 使用 `BufferTrigger` 聚合消息。
3. 按 `noteId` 分组。
4. 根据操作类型计算净增量：

```text
LIKE   -> +1
UNLIKE -> -1
```

5. 得到 `AggregationCountLikeUnlikeNoteMqDTO`，其中包含：

```text
noteId
creatorId
count
```

6. Redis 中如果存在 `count:note:{noteId}`，则更新笔记维度 `likeTotal`。
7. Redis 中如果存在 `count:user:{creatorId}`，则更新作者用户维度 `likeTotal`。
8. 发送聚合结果到 `CountNoteLike2DBTTopic`。
9. `CountNoteLike2DBConsumer` 使用 RateLimiter 削峰。
10. 使用 `TransactionTemplate` 同时更新：

```text
t_note_count.like_total
t_user_count.like_total
```

这里使用事务是因为一次点赞会同时影响笔记点赞数和作者获赞数，要保证两个 DB 更新要么一起成功，要么一起回滚。

学习重点：

- 一次点赞影响两个维度：笔记维度和用户维度。
- 聚合后写 DB，减少频繁 update。
- 落库时用事务保证两个维度一致。

## 笔记收藏数计数

入口消费者：`CountNoteCollectConsumer.java`

落库消费者：`CountNoteCollect2DBConsumer.java`

整体结构和笔记点赞数类似。

处理流程：

1. 消费 `CountNoteCollectTopic`。
2. 使用 `BufferTrigger` 聚合。
3. 按 `noteId` 分组。
4. 根据操作类型计算净增量：

```text
COLLECT    -> +1
UN_COLLECT -> -1
```

5. 更新 Redis：

```text
count:note:{noteId}.collectTotal
count:user:{creatorId}.collectTotal
```

6. 发送二级 MQ 到 `CountNoteCollect2DBTTopic`。
7. 落库消费者更新 `t_note_count.collect_total` 和 `t_user_count.collect_total`。

学习重点：

- 收藏数和点赞数几乎同构，可以对照学习。
- 重点看 DTO、枚举、Redis Field、Mapper 方法的对应关系。

## 笔记发布数计数

消费者：`CountNotePublishConsumer.java`

监听 Topic：`NoteOperateTopic`

这个消费者处理笔记发布和删除事件，消息通过 Tag 区分：

| Tag | 含义 | 计数变化 |
| --- | --- | --- |
| `publishNote` | 发布笔记 | `noteTotal +1` |
| `deleteNote` | 删除笔记 | `noteTotal -1` |

处理流程：

1. 解析 RocketMQ 原生 `Message`，获取 body 和 tags。
2. 根据 Tag 判断是发布还是删除。
3. 如果 Redis 中存在 `count:user:{creatorId}`，累加 `noteTotal`。
4. 直接调用 `UserCountDOMapper.insertOrUpdateNoteTotalByUserId` 更新 DB。

这个链路没有 BufferTrigger，也没有二级 MQ。原因是笔记发布/删除相对点赞、关注而言频率低很多，同步落库压力可控。

## 笔记评论数计数

消费者：`CountNoteCommentConsumer.java`

监听 Topic：`CountNoteCommentTopic`

处理流程：

1. 评论服务发送评论计数消息。
2. `CountNoteCommentConsumer` 使用 `BufferTrigger` 聚合。
3. 消息体解析为 `List<CountPublishCommentMqDTO>`。
4. 按 `noteId` 分组。
5. 每组数量就是该笔记评论数的增量。
6. 如果 Redis 中存在 `count:note:{noteId}`，累加 `commentTotal`。
7. 直接调用 `NoteCountDOMapper.insertOrUpdateCommentTotalByNoteId` 落库。

注意：这里没有二级 2DB MQ，聚合后直接写 DB。

## 子评论数计数

消费者：`CountNoteChildCommentConsumer.java`

该消费者用于统计一级评论下的子评论数量，Redis Field 是：

```text
childCommentTotal
```

学习时重点看它如何区分一级评论和子评论，以及如何更新评论维度计数。

## 评论点赞数计数

入口消费者：`CountCommentLikeConsumer.java`

落库消费者：`CountCommentLike2DBConsumer.java`

处理流程：

1. `CountCommentLikeConsumer` 监听 `CommentLikeUnlikeTopic`。
2. 使用 `BufferTrigger` 聚合。
3. 按 `commentId` 分组。
4. 根据操作类型计算净增量：

```text
LIKE   -> +1
UNLIKE -> -1
```

5. 如果 Redis 中存在 `count:comment:{commentId}`，累加 `likeTotal`。
6. 发送聚合结果到 `CountCommentLike2DBTTopic`。
7. `CountCommentLike2DBConsumer` 消费后用 RateLimiter 削峰落库。

## 为什么要用 BufferTrigger

点赞、收藏、粉丝、评论点赞这类计数具有高频、小粒度、可合并的特点。如果每一条行为都直接写 Redis 和 DB，会有两个问题：

1. Redis/DB 写次数过多。
2. 大 V 或热点笔记场景下，同一个 Key 被频繁更新，数据库压力很大。

`BufferTrigger` 的作用是把短时间内的多条消息聚合成一批。例如 1 秒内某个笔记发生 1000 次点赞和 200 次取消点赞，最终只需要计算净增量 `+800`，然后更新一次或少量几次计数。

这属于典型的流量聚合和削峰思路。

## 为什么要用二级 MQ

以笔记点赞数为例：

```text
CountNoteLikeTopic
    ↓
CountNoteLikeConsumer 聚合并更新 Redis
    ↓
CountNoteLike2DBTTopic
    ↓
CountNoteLike2DBConsumer 限流落库
```

二级 MQ 的价值是把“实时缓存计数”和“数据库持久化”拆开：

- 第一层消费者尽快消费行为消息，聚合后更新 Redis，保证前端读到的计数相对实时。
- 第二层消费者慢慢写 DB，可以通过 RateLimiter 控制写入速率，避免数据库被打垮。

## 为什么使用 RateLimiter

落库消费者中常见代码：

```java
private RateLimiter rateLimiter = RateLimiter.create(5000);

rateLimiter.acquire();
```

`RateLimiter` 是 Guava 的令牌桶限流器。这里用于控制消费者写数据库的速度。即使 MQ 中瞬间堆积了大量计数消息，消费者也不会无限制地打 DB，而是按固定速率处理。

这是一种削峰填谷策略：峰值流量先进入 MQ，DB 以可承受速度慢慢消费。

## 一致性模型

计数服务采用最终一致性：

1. 用户行为先在业务服务中完成，例如点赞表、关注表、评论表。
2. 业务服务发送 MQ。
3. 计数服务消费 MQ 后更新 Redis。
4. 再异步或聚合后更新 DB。

优点：

- 用户接口响应快。
- DB 写压力低。
- 高并发下更稳定。

代价：

- Redis 和 DB 在短时间内可能不一致。
- MQ 消息延迟会导致计数延迟。
- 需要依赖补偿任务或数据校准机制处理极端异常。

项目中还有 `xiaohashu-data-align` 模块，可以用于理解计数校准、热点数据对齐等后续补偿思路。

## 面试回答模板

如果面试官问“计数服务怎么设计的”，可以这样回答：

> 小哈书里的计数服务是异步计数模型。点赞、收藏、关注、评论这些行为发生后，业务服务不会同步更新所有计数字段，而是发送 RocketMQ 消息。计数服务消费消息后，对于高频场景，比如粉丝数、笔记点赞数、收藏数、评论点赞数，会先通过 BufferTrigger 按 1000 条或 1 秒做聚合，再按业务对象 ID 分组计算净增量。聚合结果先更新 Redis Hash，保证读侧计数能较快变化，然后再发二级 MQ，由落库消费者通过 RateLimiter 削峰写入 MySQL。数据库层用 `INSERT ... ON DUPLICATE KEY UPDATE` 做累加更新，部分双维度更新，比如笔记点赞数和作者获赞数，会用事务保证两个表一起更新。

继续追问“为什么不直接同步更新 DB”，可以回答：

> 因为点赞、收藏、关注这类操作高并发且可合并，同步更新 DB 会让业务接口变慢，也会让热点笔记或大 V 用户对应的计数行承受很高写压力。异步计数可以把用户请求和计数持久化解耦，Redis 承接实时读，MQ 和 BufferTrigger 做削峰聚合，DB 最终一致即可。

## 重点学习顺序

1. 先看 `MQConstants.java` 和 `RedisKeyConstants.java`，理解 Topic 和 Redis Hash 设计。
2. 看 `CountFansConsumer.java`，这是最标准的 `BufferTrigger -> Redis -> 二级 MQ` 链路。
3. 看 `CountFans2DBConsumer.java`，理解 RateLimiter 和落库。
4. 看 `CountNoteLikeConsumer.java` 和 `CountNoteLike2DBConsumer.java`，理解笔记维度 + 用户维度双写。
5. 看 `CountNoteCollectConsumer.java`，对照点赞链路学习。
6. 看 `CountFollowingConsumer.java` 和 `CountNotePublishConsumer.java`，理解为什么有些链路不做聚合。
7. 看 `UserCountDOMapper.xml` 和 `NoteCountDOMapper.xml`，理解累加落库 SQL。

