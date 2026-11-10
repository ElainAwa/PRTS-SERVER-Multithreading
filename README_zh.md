# PRTS 服务端

基于 **NeoForge 21.1.250** 的 **Minecraft 1.21.1** 服务端，可同时加载 Bukkit/Spigot 插件与 NeoForge 模组。
> English: [README.md](README.md)


## 本分支（`base/new-kernel`）

本分支在上游代码之上开发**下一代 PRTS 服务端内核 —— 一个双轴调度内核**。

内核围绕两条轴展开：

- **轴 A · 等**：等待点注册表。每个等待点都要说明产出者、进展读数、超时动作和退化落点；等待上界来自实测，不作假设。
- **轴 B · 算**：按域计量份额，并维护份额表与降级阶梯。某个域开始停滞时，削减其份额，而不是拖慢整个 tick。
- **计划与提交**：逐 tick 计划、作业图与调度器，以及提交账 —— 提交顺序与计划顺序一致。
- **安全与观测**：写路径上的安全网与零生效检测，以及只读观测键，如实反映内核观测到的内容。

**发布目标：通过验证的内核路径与能力集默认启用。** 在此之前，各项能力逐个置于开关之后验证：默认配置下行为与基座完全一致，每次改动都会附上与前一版的等价核对。通过验证的能力纳入默认路径；未通过验证或呈现负收益的实验项保留关闭，或由正式路径替代。

沿用自基座的部分：

- 干净的上游代码，功能性改动只有 PRTS 品牌与日志着色；
- 单一固定的构建工具链，产出的字节码形态稳定（编译期 Mixin 注解处理器**特意固定**，见 `gradle/libs.versions.toml`）。

平台：**仅 NeoForge** —— 本线仅支持并验证 NeoForge。`arclight-fabric` 模块仍保留在代码树中，但不参与本轮的设计、验证与行为对齐：Fabric 支持推迟到新内核在 NeoForge 上验证通过之后。本线**不支持 Forge**。

其他分支：

- `legacy/old-framework` —— 上一代多线程框架，保留作参考与存档。

状态：`0.1.0-SNAPSHOT`，开发中，不保证稳定。内核分阶段推进；各项能力仍在逐项验证，验证通过后转为默认启用。不承诺生产稳定性。

## 环境要求

- Java 21
- NeoForge 21.1.250 服务端环境（启动器会自动选用匹配的 libraries）

## 构建

```bash
./gradlew collect          # 完整构建；产出的服务端 jar 在 bootstrap/build/libs/
```

回归测试源码不在本公开仓库中分发。

## 运行

```bash
java -Xms1G -Xmx4G -jar PRTS-neoforge-1.21.1-0.1.0-SNAPSHOT.jar -nogui
```

- Bukkit/Spigot 插件放入 `plugins/`
- NeoForge 模组放入 `mods/`

## 链接

- 仓库：https://github.com/ElainAwa/PRTS-SERVER-Multithreading
- 发布：https://github.com/ElainAwa/PRTS-SERVER-Multithreading/releases

## 许可

GPL-3.0，见 `LICENSE`；归属与第三方声明见 `NOTICE`、`THIRD-PARTY.md`、`UPSTREAM.md`。
