# 简历项目取舍与面试追问记录

本文档整理本次侧聊中的前三个完整问答，主要围绕小哈书项目简历内容如何取舍，以及面试官可能追问的问题。

## 1. 网关和认证模块是否有必要写到简历

结论：可以写，但不建议作为核心亮点大写。

小哈书项目真正更有竞争力的亮点是：

- 分布式 ID 服务
- 笔记高并发读写
- 点赞、收藏、关注的 Redis + MQ 异步化
- 计数服务聚合写入
- Canal + Elasticsearch 搜索同步，如果自己学熟再写

网关和认证模块更适合放在技术栈或基础能力描述中。

推荐写法：

```text
Spring Cloud Gateway + Nacos + Feign + JWT/Redis 鉴权
```

如果想在项目内容里补一句，可以写：

```text
使用 Spring Cloud Gateway 统一入口，实现路由转发、登录态校验、用户上下文透传；认证模块支持验证码登录、Token 签发、权限信息缓存及 Feign 调用链路中的用户身份传递。
```

### 可能被问的问题

1. 网关在项目里负责什么？

答：统一入口、路由转发、鉴权拦截、请求头透传、跨域、日志等。业务服务不用重复做登录校验。

2. 为什么要把鉴权放在网关？

答：减少各业务服务重复鉴权逻辑；外部请求先经过网关，非法请求不会打到后端服务；方便统一维护白名单、Token 校验和用户 ID 透传。

3. 用户登录后，后端怎么识别当前用户？

答：认证服务登录成功后生成 Token，客户端后续请求携带 Token；网关解析或校验 Token 后，把 `userId` 放到请求头；业务服务通过过滤器把请求头里的用户 ID 放到 `LoginUserContextHolder`。

4. Feign 调用时用户身份怎么传递？

答：项目里有 Feign 拦截器，会把当前请求上下文中的用户 ID 继续放到下游请求头里，保证跨服务调用时还能拿到登录用户身份。

5. 认证服务和用户服务是什么关系？

答：认证服务负责登录、验证码、Token、权限校验；用户服务负责用户资料、注册、密码等用户数据。认证服务需要通过 RPC/Feign 调用用户服务查询用户信息。

6. 权限信息为什么要放 Redis？

答：权限数据读多写少，放 Redis 可以减少每次鉴权查库；系统启动或权限变更时同步到 Redis，网关或认证模块直接读取缓存。

7. Token 用 JWT 还是 Redis Session？区别是什么？

答：JWT 优点是无状态，网关校验方便；缺点是主动失效和续期麻烦。Redis Session 优点是可控、易失效，缺点是依赖 Redis。项目如果使用 Redis 管理登录态，就强调可控性和登出失效。

建议：如果对 Spring Security、JWT、Gateway Filter、权限模型不熟，不要把网关认证包装成核心亮点。面试时把重点收回到“统一鉴权 + 用户上下文透传 + Feign 链路传递”即可。

## 2. 当前简历内容中不写评论和搜索是否合理

结论：不写评论服务是合理的；如果 Elasticsearch 还没学会，就不要写在技术栈里。

### 关于评论服务

评论服务如果涉及 Cassandra，而自己没有把握，不建议放到简历里。面试官可能追问：

- 为什么评论不用 MySQL，而用 Cassandra？
- Cassandra 的分区键怎么设计？
- 热门笔记评论很多，怎么避免热点分区？
- 一级评论、二级评论怎么建模？
- 评论分页怎么做？
- 删除评论、点赞评论怎么保证一致性？

如果这些问题答不稳，就不要主动暴露。

### 关于 Elasticsearch

如果简历技术栈中写了 `Canal + Elasticsearch`，即使项目内容没有展开搜索服务，面试官也可能追问：

- ES 在项目里负责什么？
- 索引怎么设计？
- MySQL 和 ES 怎么保持一致？
- Canal 监听 Binlog 后怎么处理增删改？
- 搜索如何做中文分词、高亮、排序、过滤？
- ES 数据丢了怎么重建？

如果还没学会，建议直接从技术栈中删掉 `Canal + Elasticsearch`。

### 更稳的技术栈写法

```text
Spring Cloud Alibaba + Nacos + Gateway + Feign + MyBatis + MySQL + Redis + RocketMQ + ZooKeeper
```

当前简历最稳的主线：

1. 分布式 ID 服务：Leaf 号段 + 雪花算法 + ZooKeeper workerId。
2. 笔记服务高并发读：Redis + Caffeine 二级缓存、缓存穿透、随机过期、RocketMQ 广播删本地缓存、CompletableFuture 并发 RPC。
3. 点赞高并发写：Redis 布隆过滤器、ZSET、Lua、MQ 异步落库、RateLimiter 削峰、最终一致。
4. 用户关系 + 计数服务：关注/取关顺序消息、Redis ZSET、Lua 原子性；计数服务 BufferTrigger 聚合、Redis 计数、异步落库。

建议：不要为了技术栈看起来更完整，把自己不熟的 ES 或 Cassandra 写上去。Redis、MQ、分布式 ID、计数聚合已经足够支撑项目亮点。

## 3. ZooKeeper 是否有必要单独写

结论：技术栈里可以保留 ZooKeeper，但项目内容里不要单独展开成一个核心亮点。

项目中 ZooKeeper 的作用比较明确：主要用于 Leaf 雪花算法中管理 ID 生成服务实例的 `workerId`，不是自己基于 ZooKeeper 做了复杂的注册中心、分布式锁、选主或配置中心。

推荐写法：

```text
项目技术栈：Spring Cloud Alibaba + Nacos + Gateway + Feign + MyBatis + MySQL + Redis + RocketMQ + ZooKeeper
```

在分布式 ID 这一条里带一句即可：

```text
整合美团 Leaf 搭建分布式 ID 生成服务，支持号段模式和雪花算法；雪花算法通过 ZooKeeper 管理 workerId，避免多节点 ID 冲突。
```

### 可能被问的问题

1. 项目里 ZooKeeper 用来做什么？

答：主要用于 Leaf 雪花算法中管理 ID 生成服务实例的 `workerId`。每个 ID 服务节点启动时，会在 ZooKeeper 上注册持久顺序节点，节点序号作为 workerId，保证不同 ID 服务实例生成 ID 时不会冲突。

2. workerId 是业务服务机器号还是 ID 服务机器号？

答：是 ID 生成服务实例的机器号，不是笔记服务、用户服务这些业务服务的机器号。业务服务只是通过 Feign 调用 ID 服务拿 ID。

3. 为什么不用 Nacos 管理 workerId？

答：Nacos 主要用于服务注册发现和配置中心，不适合做强一致的顺序编号分配。ZooKeeper 有顺序节点和一致性保证，适合做 workerId 分配。

4. ZooKeeper 挂了还能生成 ID 吗？

答：项目里会把 workerId 缓存在本地文件。如果 ZooKeeper 初始化失败，会尝试读取本地 workerId 作为兜底。但这种方式更适合已有节点重启恢复，不适合新节点扩容。生产上仍然要保证 ZooKeeper 高可用。

5. 为什么是持久顺序节点，不是临时节点？

答：持久节点可以让同一个 `ip:port` 重启后复用原 workerId，避免每次重启都分配新编号。顺序节点可以由 ZooKeeper 自动生成全局递增序号，天然避免多个实例抢同一个 workerId。

总结：ZooKeeper 可以写，但只作为 Leaf 雪花算法 workerId 管理的支撑组件，不要包装成项目核心亮点。

