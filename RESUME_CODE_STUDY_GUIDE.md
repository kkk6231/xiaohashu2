# 小哈书简历项目代码学习清单

## 结论

从当前代码来看，简历中提到的核心技术点大部分都有对应实现，不是纯描述型项目。已能在代码中看到分布式 ID、笔记发布/详情/更新/删除、点赞/收藏、关注/取关、计数聚合、搜索索引、Canal 增量同步、RocketMQ 异步化、Redis/Lua/ZSET/布隆过滤器、Caffeine 本地缓存、CompletableFuture 并发 RPC、RateLimiter 削峰等实现。

需要注意两点：

1. “JMeter 压测单机单节点 22000+ QPS，平均 4 ms”当前仓库没有看到 `.jmx`、压测报告或结果文件，代码能支撑 Leaf ID 服务，但这个性能数字需要你自己保留压测截图/报告作为依据。
2. “高可用、高扩展性、海量用户”属于架构能力描述，代码中有微服务拆分、Nacos/Gateway/Feign/RocketMQ/Redis/ES 等基础，但面试时要能解释这些组件如何支撑扩展，而不是只说“用了这些技术”。

## 简历内容实现对照

| 简历描述 | 当前实现情况 | 重点代码 |
| --- | --- | --- |
| Spring Cloud Alibaba + Nacos + Gateway + Feign | 已有多模块微服务、`bootstrap.yml` Nacos 配置、Gateway 模块、Feign API 模块 | `xiaohashu-gateway/`、各服务 `src/main/resources/config/bootstrap.yml`、各 `*-api` 模块 |
| MyBatis + MySQL | 已实现 DO、Mapper、XML 映射 | 各服务 `domain/mapper`、`resources/mapper` |
| Redis | 广泛用于登录、缓存、关系链、计数、布隆/ZSET | 各服务 `RedisKeyConstants`、`RedisTemplateConfig` |
| RocketMQ | 已实现生产者、消费者、顺序消息、广播消息 | 各服务 `consumer`、`MQConstants`、`RocketMQConfig` |
| 分布式 ID：Leaf 号段 + 雪花 | 已实现 Leaf Controller、segment、snowflake、ZK holder | `xiaohashu-distributed-id-generator/.../LeafController.java`、`core/segment`、`core/snowflake` |
| 22000+ QPS/4ms | 仓库未见压测报告，不能仅凭代码确认 | 需要补充 JMeter 文件或压测截图 |
| 笔记发布、编辑、查询 | 已实现 | `xiaohashu-note/.../NoteServiceImpl.java`、`NoteController.java` |
| CompletableFuture 并发调用下游服务 | 已实现于笔记详情，用户信息和 KV 内容并发查询 | `NoteServiceImpl.findNoteDetail` |
| Redis + Caffeine 二级缓存 | 已实现笔记详情本地缓存 + Redis 缓存 | `NoteServiceImpl.LOCAL_CACHE`、`findNoteDetail` |
| 更新/删除后 RocketMQ 广播删本地缓存 | 已实现广播消费者 | `DeleteNoteLocalCacheConsumer.java`、`NoteServiceImpl.deleteNoteLocalCache` |
| 点赞高并发写：布隆过滤器 | 已实现 Redis + Lua 布隆判断 | `NoteServiceImpl.likeNote/unlikeNote`、`resources/lua/bloom_note_*` |
| 点赞高并发写：Redis ZSET + MQ 异步落库 | 已实现 ZSET 维护近期点赞/收藏，MQ 异步写 DB | `NoteServiceImpl`、`LikeUnlikeNoteConsumer.java` |
| 消费者 RateLimiter 削峰 | 已实现 | `LikeUnlikeNoteConsumer.java`、计数 `*2DBConsumer.java` |
| 用户关系：关注/取关高并发写 | 已实现 | `xiaohashu-user-relation/.../RelationServiceImpl.java` |
| 用户关系：Redis 缓存 + 顺序 MQ 异步存库 | 已实现 `asyncSendOrderly`，按 userId 做 hashKey | `RelationServiceImpl.follow/unfollow`、`FollowUnfollowConsumer.java` |
| 用户关系：Lua 原子操作 | 已实现 | `xiaohashu-user-relation/.../resources/lua/*.lua` |
| 计数服务：消费行为 MQ | 已实现粉丝、关注、点赞、收藏、评论等计数消费者 | `xiaohashu-count/.../consumer` |
| 计数服务：BufferTrigger 聚合 1000 条/1s | 已实现多个聚合消费者 | `CountFansConsumer.java`、`CountNoteLikeConsumer.java` 等 |
| 计数服务：先写 Redis，再异步落库 | 已实现 Redis increment + 发送 2DB MQ | `CountFansConsumer.java`、`CountFans2DBConsumer.java` |
| 搜索服务：笔记、用户索引 | 已实现 index 常量和重建文档逻辑 | `NoteIndex.java`、`UserIndex.java`、`NoteServiceImpl.rebuildDocument`、`UserServiceImpl.rebuildDocument` |
| Canal 监听 MySQL Binlog 增量索引 | 已实现 CanalConnector 拉取、ack/rollback、按表处理事件 | `CanalClient.java`、`CanalSchedule.java` |
| ES 搜索中文分词、高亮、多维筛选 | 搜索代码实现 multi_match、高亮、类型/时间筛选、排序；分词更多依赖 ES index mapping/IK 配置，需结合部署配置说明 | `search/biz/service/impl/NoteServiceImpl.java`、`UserServiceImpl.java` |

## 你应该重点学习的代码

### 1. 分布式 ID 服务

先看 `xiaohashu-distributed-id-generator/xiaohashu-distributed-id-generator-biz`。

重点文件：

- `controller/LeafController.java`：对外暴露 `/id/segment/get/{key}` 和 `/id/snowflake/get/{key}`。
- `core/segment/SegmentIDGenImpl.java`：号段模式核心，重点理解双 buffer、异步加载下一号段、DB 步长。
- `core/snowflake/SnowflakeIDGenImpl.java`：雪花算法生成逻辑，重点理解时间戳、workerId、sequence、时钟回拨。
- `core/snowflake/SnowflakeZookeeperHolder.java`：通过 ZooKeeper 管理 workerId。

面试要能讲清楚：为什么不用数据库自增 ID；号段模式如何减少 DB 压力；雪花算法如何保证趋势递增和唯一；时钟回拨怎么处理。

### 2. 笔记详情读链路

重点文件：`xiaohashu-note/xiaohashu-note-biz/src/main/java/com/quanxiaoha/xiaohashu/note/biz/service/impl/NoteServiceImpl.java`。

重点方法：

- `publishNote`：发布笔记，调用 ID 服务生成 noteId，调用 KV 服务保存正文，写 MySQL，发送笔记操作 MQ。
- `findNoteDetail`：先查 Caffeine 本地缓存，再查 Redis，再查 DB；DB 命中后用 CompletableFuture 并发查用户和内容。
- `updateNote`、`deleteNote`、`updateNoteVisibleOnlyMe`：更新后删除 Redis 缓存并发送广播 MQ 删除各节点本地缓存。
- `deleteNoteLocalCache`：本地缓存删除入口。

面试要能画出读流程：本地缓存 -> Redis -> DB/RPC -> 回填 Redis/本地缓存。还要能解释缓存穿透、随机过期时间、广播删除本地缓存的原因。

### 3. 点赞/收藏高并发写

仍然重点看 `NoteServiceImpl`，再配合 Lua 和消费者。

重点文件：

- `resources/lua/bloom_note_like_check.lua`
- `resources/lua/bloom_add_note_like_and_expire.lua`
- `resources/lua/note_like_check_and_update_zset.lua`
- `consumer/LikeUnlikeNoteConsumer.java`
- `consumer/CollectUnCollectNoteConsumer.java`

重点理解：

- 布隆过滤器用于快速判断“绝对没点过赞/收藏”，减少 DB 查询。
- ZSET 用于维护用户近期点赞/收藏列表，score 是操作时间。
- MQ 异步落库，让接口快速返回。
- `RateLimiter` 在消费者中控制入库速率，避免数据库被瞬时流量打穿。
- 数据库唯一索引/更新语句保证最终幂等。

### 4. 用户关注/取关关系链

重点文件：

- `xiaohashu-user-relation/xiaohashu-user-relation-biz/src/main/java/com/quanxiaoha/xiaohashu/user/relation/biz/service/impl/RelationServiceImpl.java`
- `consumer/FollowUnfollowConsumer.java`
- `resources/lua/follow_check_and_add.lua`
- `resources/lua/unfollow_check_and_delete.lua`
- `resources/lua/follow_batch_add_and_expire.lua`

重点理解：

- 关注列表用 Redis ZSET 存储，score 是关注时间。
- 关注/取关先操作 Redis，再发送顺序 MQ 异步入库。
- `asyncSendOrderly` 使用 userId 作为 hashKey，保证同一用户的关注/取关顺序。
- Lua 把“检查是否已关注 + 新增/删除 + 限制关注上限”放在一次 Redis 调用里，保证原子性。

### 5. 计数服务

重点目录：`xiaohashu-count/xiaohashu-count-biz/src/main/java/com/quanxiaoha/xiaohashu/count/biz/consumer`。

优先看：

- `CountFansConsumer.java`
- `CountFollowingConsumer.java`
- `CountNoteLikeConsumer.java`
- `CountNoteCollectConsumer.java`
- 对应的 `*2DBConsumer.java`

重点理解：

- 第一层消费者消费业务事件 MQ。
- `BufferTrigger` 按 1000 条或 1 秒聚合一次，减少 Redis/DB 写频率。
- 聚合后先更新 Redis 计数，再发送 `*_2_DB` MQ。
- 第二层消费者使用 `RateLimiter` 削峰并落库。

面试要能解释：为什么计数可以最终一致；为什么不在点赞/关注接口里同步更新 DB 计数；Redis 和 DB 不一致时怎么补偿。

### 6. 搜索服务和 Canal 增量同步

重点目录：`xiaohashu-search/xiaohashu-search-biz`。

重点文件：

- `canal/CanalClient.java`：创建 Canal 连接，订阅 Binlog。
- `canal/CanalSchedule.java`：定时拉取 Canal 消息，处理 `t_note`、`t_user` 变更，写入或删除 ES 文档。
- `service/impl/NoteServiceImpl.java`：笔记搜索，多字段匹配、权重、时间筛选、类型筛选、排序、高亮。
- `service/impl/UserServiceImpl.java`：用户搜索，昵称/小哈书 ID 查询，高亮，按粉丝数排序。
- `index/NoteIndex.java`、`index/UserIndex.java`：索引字段定义。
- `resources/mapper/SelectMapper.xml`：从 MySQL 拼出 ES 文档所需字段。

面试要能讲清楚：为什么搜索数据不直接查 MySQL；Canal 如何实现 DB 到 ES 的最终一致；新增、更新、删除分别怎么处理；高亮和多维筛选在 ES 查询里怎么写。

### 7. 基础设施和微服务调用

这些不是最亮眼的项目亮点，但面试被问到时要能兜住。

重点看：

- `xiaohashu-gateway/src/main/resources/application.yml`：网关路由、服务发现、鉴权相关配置。
- 各服务 `bootstrap.yml`：Nacos 注册与配置中心。
- 各 `*-api` 模块：Feign 接口定义。
- `xiaoha-framework/xiaoha-spring-boot-starter-biz-context`：用户上下文、Feign 请求头透传。

重点理解：网关如何路由到服务；登录用户 ID 如何从网关/请求头传递到业务服务；服务之间为什么拆 `api` 模块。

## 建议学习顺序

1. 先跑通项目结构：根 `pom.xml`、每个服务的 `pom.xml`、Nacos/Gateway/Feign 配置。
2. 学 ID 服务：它最独立，适合作为第一个面试亮点。
3. 学笔记服务读链路：二级缓存、缓存一致性、CompletableFuture 是核心。
4. 学点赞/收藏写链路：Redis 布隆、ZSET、Lua、MQ、RateLimiter 串起来讲。
5. 学关注/取关：重点对比它和点赞链路的相同点/不同点，尤其是顺序消息。
6. 学计数服务：重点讲异步聚合、最终一致、削峰落库。
7. 学搜索服务：重点讲 Canal + ES 增量索引和搜索查询 DSL。

## 简历表述优化建议

可以保留大部分原简历内容，但建议把性能数字写得更稳一点：

原句：

> 使用 Jmeter 压测单机单节点，接口的吞吐量可达到 22000+ QPS，平均响应耗时 4 ms。

如果你没有压测报告，建议改成：

> 使用 JMeter 对 Leaf ID 生成接口进行压测，在本地/单节点环境下验证号段模式的高吞吐能力，并基于压测结果调优线程池、连接池及号段步长。

如果你能补充报告，再写具体数字。

另外，“使用 Redis 布隆过滤器”这句话可以在面试里补充说明：代码中是通过 Redis + Lua 脚本实现布隆相关判断和初始化，不是简单调用一个现成布隆过滤器 API。

## 面试前必须会讲的 8 个问题

1. Leaf 号段模式为什么 QPS 高？双 buffer 是怎么减少 DB 访问的？
2. 雪花算法的 ID 由哪些部分组成？时钟回拨怎么办？
3. 笔记详情查询为什么要用 Caffeine + Redis 二级缓存？
4. 更新/删除笔记后，多个服务实例的本地缓存怎么保证删除？
5. 点赞接口为什么用布隆过滤器、ZSET、MQ，而不是直接写 MySQL？
6. 关注/取关为什么要用顺序消息？hashKey 为什么选 userId？
7. 计数服务为什么要聚合写？Redis 和 DB 最终一致如何保证？
8. Canal 同步 ES 时，新增、更新、删除事件分别怎么处理？

