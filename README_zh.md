# PRTS 服务端

基于 **NeoForge 21.1.250** 的 **Minecraft 1.21.1** 服务端，同时支持 Bukkit/Spigot 插件与 NeoForge/Fabric 模组。

> 状态：新内核基座线，版本 `0.1.0-SNAPSHOT`。

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
