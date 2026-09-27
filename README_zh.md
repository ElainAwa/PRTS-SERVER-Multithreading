# PRTS 服务端

基于 **NeoForge 21.1.250** 的 **Minecraft 1.21.1** 服务端，同时支持 Bukkit/Spigot 插件与 NeoForge/Fabric 模组。
> English: [README.md](README.md)


## 本分支说明（`base/new-kernel`）

本分支是**下一代 PRTS 服务端多线程内核（多线程调度内核）的基座**，刻意保持干净最小：

- **干净的上游基座**，功能性改动只有 PRTS 品牌与日志/横幅着色；
- **单一固定构建工具链**，保证产出的字节码形态稳定（编译期 Mixin 注解处理器是**有意 pin 死**的，见 `gradle/libs.versions.toml`）；
- **尚无内核代码**：新的**多线程**调度内核将从这里开始开发。

平台：**NeoForge**（主）与 **Fabric**；本线**不支持 Forge**。

其它分支：

- `legacy/old-framework` —— 上一代多线程框架线，保留作参考与历史。

状态：`0.1.0-SNAPSHOT`，开发中，不保证稳定。

## 环境要求

- Java 21
- NeoForge 21.1.250 服务端环境（启动器会使用与之匹配的 libraries）

## 构建

```bash
./gradlew collect          # 完整构建，服务端 jar 产物在 bootstrap/build/libs/
```

## 运行

```bash
java -Xms1G -Xmx4G -jar PRTS-neoforge-1.21.1-0.1.0-SNAPSHOT.jar -nogui
```

- Bukkit/Spigot 插件放入 `plugins/`
- NeoForge/Fabric 模组放入 `mods/`

## 链接

- 仓库：https://github.com/ElainAwa/PRTS-SERVER-Multithreading
- 发布：https://github.com/ElainAwa/PRTS-SERVER-Multithreading/releases

## 许可

GPL-3.0，见 `LICENSE`；归属与第三方声明见 `NOTICE`、`THIRD-PARTY.md`、`UPSTREAM.md`。
