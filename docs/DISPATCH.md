# 多订单多人派单模块

此模块已实现为独立服务层，当前不接入 CupFlow 的比赛 Demo 页面。

## 模式

- `semi_auto`：返回最优员工建议，不改变订单状态。
- `auto`：只向在线且当前没有进行中订单的员工派送；重复调用同一订单会幂等返回原结果。

## 策略

候选员工必须在线、`activeOrderCount = 0`。系统优先选择覆盖订单所需技能的员工；同等条件下选择空闲更久的员工。当前为单进程内存实现，适用于本地 Demo 和后续 KDS 接入前的模块验证。

## API

```bash
# 查看模拟队列、员工与 Trace
curl http://localhost:3000/api/dispatch/state

# 半自动推荐
curl -X POST http://localhost:3000/api/dispatch/recommend \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"A102","mode":"semi_auto"}'

# 自动派送
curl -X POST http://localhost:3000/api/dispatch/recommend \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"A102","mode":"auto"}'

# 完成订单并释放员工
curl -X POST http://localhost:3000/api/dispatch/complete \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"A102","workerId":"W001"}'

# 运行策略评测
curl http://localhost:3000/api/dispatch/evaluate
```

## 当前边界

本模块不包含真实 KDS/POS、登录认证、角色权限、数据库或跨浏览器实时同步。生产接入时应以数据库事务/分布式锁替代内存锁，并由身份服务提供员工身份与权限。
