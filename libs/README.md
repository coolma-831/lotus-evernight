# libs/

放 Evernight 的发行 jar：

```
evernight-<evernight_version>.jar
```

`gradle.properties` 里的 `evernight_version` 决定文件名。默认由 `gradlew build` 在配置阶段
从 Evernight 的 GitHub Release 下载；也可以自行构建本体，把
`build/libs/evernight-<version>.jar` 复制到这里覆盖下载结果。

`modImplementation` 会让 Loom 把这份 intermediary jar 重映射成开发用的 named 命名空间。
不要换成 `build/devlibs` 下的 `-dev.jar`：它的 access widener 是 named 命名空间，
Loom 会以 `Namespace mismatch, expected intermediary got named` 失败。

`*.jar` 已被 `.gitignore` 忽略，不要提交进仓库。
