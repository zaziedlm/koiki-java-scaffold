# KOIKI Framework 採用方針（案）

状態：**DRAFT**（§5 決定事項反映、§5.2 識別子はOwner確認待ち）
作成日：2026-11-12

## 1. 目的と位置づけ

`koiki-java-scaffold`は、KOIKI-JavaWeb-FW（以下Framework）を採用する業務アプリケーションの**開発の土台**である。
本書は、Frameworkの取得方式・版固定・環境別準備・土台の初期構成について、実装前に合意する方針をまとめる。

- Frameworkのsource repositoryは**参照のみ**とする。本repositoryからFrameworkへcommit・push・PRを行わない。
- 本repositoryはFramework source・Root Reactor・`relativePath`に依存せず、配布されたbinaryだけで構築できること。
- Framework側の検証・承認手続（Framework repositoryの`AGENTS.md`）は本repositoryには適用しない。本repositoryの規則は本repositoryの`AGENTS.md`／`CLAUDE.md`に置く。

## 2. Framework取得方式

Framework提供の**Release asset一式配布**（固定版ZIP）を採用する。GitHub Packages、開発者ごとのPATは使わない。

| 区分 | 配布元 | 認証 | 適用 |
| --- | --- | --- | --- |
| **方式①（採用）** | public `github.com/zaziedlm/KOIKI-JAVAWEB` のRelease | **不要** | 本repository（github.com上の土台） |
| 方式② | 社内GitHub Enterpriseへ持ち込んだprivate repositoryのRelease | 開発者：`gh auth login`（社内SSOのbrowser認証、PAT手作成不要）／CI：読取資格情報1つ | 社内GHEへ持ち込んだ土台・案件 |

### 2.1 配布物

ZIP `koiki-framework-<version>.zip`（Release tag `framework-<version>`）に次を同梱する。

- Maven repository配置のKOIKI artifact一式：14 POM（うち12はJAR付き）
- `SHA256SUMS`、`manifest.json`
- 取込script `prepare-koiki.ps1`、`README.md`

全moduleは同一版。異なる配布物のmoduleを混在させない。

### 2.2 方式①の取得手順（概要）

1. ZIPをRelease URLから認証なしで取得する。
2. ZIP全体のSHA-256を本repositoryに記録した固定値と照合する。
3. 同梱の`prepare-koiki.ps1`を`-LocalArchive`で実行し、内部payloadのhash照合とMaven local repositoryへの登録を行う。
4. 通常どおり`./mvnw verify`でbuildする。

1〜3は本repositoryの取得wrapper（`tools/`配下に新設予定）で一括実行し、`gh`認証を不要にする。
`prepare-koiki.ps1`は本repositoryにレビュー済みcopyを保持し、取得したZIPのものと一致することも確認する。

### 2.3 方式②との差分

- wrapperは取得部分だけを切り替える（`gh release download`、GHE hostは環境変数`GH_HOST`で指定）。
  同梱scriptの`-Repository`は`owner/repo`形式のみ受け付けるため、host指定は`GH_HOST`で行う。
- CIは別private repositoryを`GITHUB_TOKEN`で読めないため、読取専用資格情報を1つSecretsへ置く（`GH_TOKEN`）。
  形態（GitHub App／machine user／fine-grained token）は社内GHE方針で決める。
- 版固定・hash照合・取込以降は方式①と同一。

## 3. 版固定

| 項目 | 初期値 | 管理場所（予定） |
| --- | --- | --- |
| Framework版 | `0.1.0-preview.1` | `pom.xml`の`koiki.version` property |
| ZIP SHA-256 | `470a2fac4a30d3c323e38341b855e23e263c7aced501c3c8a378e06db1a8b27c` | `tools/koiki-distribution.properties` |
| 配布元 | `zaziedlm/KOIKI-JAVAWEB` | 同上 |

- 2026-11-12、Claude cloud sessionで未認証取得（HTTP 200）とSHA-256を観測した値。採用時に再確認する。
- `0.1.0-preview.1`はPhase 5の正式／support付き提供ではない。preview版としての制約を受け入れて採用する。
- SNAPSHOTは追従しない。版更新はwrapper設定・`koiki.version`・hashを同時に変更するPRで行う。

## 4. 環境別の準備

| 環境 | 前提ツール | 手順 | 資格情報 |
| --- | --- | --- | --- |
| 開発PC（Windows想定） | JDK 21、PowerShell 7 | 初回にwrapperを1回実行→IDEは通常の`~/.m2`を参照 | 不要 |
| GitHub Actions | `actions/setup-java`（Temurin 21）、pwsh（runner標準） | build前にwrapper実行 | 不要 |
| Claude cloud環境 | JDK 21、pwsh（現行imageに存在） | 環境のsetup scriptでwrapper実行 | 不要。github.comとMaven Centralへの到達のみ |

- 取込先は既定の`~/.m2/repository`。IDEとの整合を優先し、project-local repositoryは使わない。
- third-party依存とMaven pluginは通常のMaven Centralから取得する。

## 5. 決定事項（2026-11-12 Owner判断）

| No. | 事項 | 決定 |
| --- | --- | --- |
| 1 | Parent | **`koiki-parent`を採用**（社内共通Parentは現存しない）。`<relativePath/>`を明示 |
| 2 | starter | **Referenceアプリ（Spring MVC系）を稼働させるに足る構成**（§5.1） |
| 3 | 識別子 | 土台とわかる単純な値（§5.2、提案） |
| 4 | DB・実行形態 | PostgreSQLはCompose／Testcontainers。実行可能JARのローカル実行に加え、AWS向けのコンテナ化を前提にローカルでcontainer buildできる構成とする（§5.3） |
| 5 | 取得経路の実証 | 「空のlocal repository→取得→取込→build」を本repositoryで実証・記録する（§7） |
| 6 | 方式② | 社内GHEでの試行が必要。方式①の手順整理に合わせ、進められる部分から対応する |

### 5.1 starter構成

Reference（`koiki-reference-app`）の直接依存のうち、Spring MVC系の稼働に必要なものを採用する。

| scope | artifact | 備考 |
| --- | --- | --- |
| compile | `koiki-architecture-contract` | module契約 |
| compile | `koiki-starter-web-mvc` | Spring MVC＋Thymeleaf |
| compile | `koiki-starter-session-jdbc` | JDBC Session。推移的に`identity`→`security`／`audit`／`data-jpa`、`data`を含む |
| runtime | `org.postgresql:postgresql`、`flyway-database-postgresql` | 版はKOIKI BOM管理 |
| test | `koiki-archunit-rules`、`koiki-testing`、`spring-boot-starter-test`、`spring-boot-starter-webmvc-test`、`spring-boot-starter-security-test` | |

- `koiki-starter-api`（REST／Bearer）と`koiki-starter-observability`は初期構成に含めない。REST API・監視が必要になった時点で追加する。
- Referenceの業務機能（expense等）・`spring-boot-starter-cache`等の業務都合の依存は移植しない。
- local認証のHMAC鍵等、Starterが要求する設定値は環境変数で与え、値をrepositoryに置かない。

### 5.2 識別子（提案）

| 項目 | 提案値 |
| --- | --- |
| groupId | `com.example.scaffold` |
| artifactId | `koiki-java-scaffold` |
| version | `0.1.0-SNAPSHOT` |
| base package | `com.example.scaffold` |
| main class | `ScaffoldApplication` |
| `spring.application.name` | `koiki-java-scaffold` |
| DB名／user | `scaffold`／`scaffold`（開発用、passwordは環境変数） |
| Flyway location／履歴table | `classpath:db/migration/scaffold`／`scaffold_flyway_history` |
| container image | `koiki-java-scaffold:local` |

- `com.example`は「置換前提の土台」であることを示す。案件ではgroupId・package・artifactIdを一括置換する（手順はREADMEに記載）。
- Frameworkの`org.koikifw`名前空間は使わない。

### 5.3 実行形態とcontainer

| 用途 | 構成 |
| --- | --- |
| test | Testcontainers（PostgreSQL）。`./mvnw verify`で起動 |
| ローカル実行（JAR） | `compose.yaml`でPostgreSQLだけ起動→`java -jar target/*.jar` |
| ローカル実行（container） | 同じ`compose.yaml`のprofileでapp containerも起動 |
| container build | `Dockerfile`は実行専用。JARをhostでbuildしてからimage化する（Framework D3方式1の構成に準拠：Temurin 21 JRE、layer展開、非root、SIGTERM） |

- base imageはdigestで固定する。AWS向けの固有設定（ECR・ECS等）は本土台に含めず、案件で追加する。
- Docker Desktop等のcontainer実行環境は開発PCの前提ツールに追加する。

## 6. 土台の初期構成（案）

```
koiki-java-scaffold/
├─ AGENTS.md / CLAUDE.md        本repositoryの作業規則（Frameworkは参照のみ等）
├─ README.md                    準備手順・build・実行方法、識別子の置換手順
├─ mvnw / mvnw.cmd / .mvn/      Maven Wrapper（Framework baselineと同じMaven 3.9.16）
├─ pom.xml                      koiki-parentをparentに指定（<relativePath/>）
├─ compose.yaml                 PostgreSQL（＋app profile）
├─ Dockerfile                   実行専用image
├─ tools/
│  ├─ prepare-koiki-distribution.ps1   取得・照合・取込wrapper
│  ├─ prepare-koiki.ps1                 同梱scriptのレビュー済みcopy
│  └─ koiki-distribution.properties     版・hash・配布元
├─ src/main/java/com/example/scaffold/  最小Spring MVC application
├─ src/main/resources/          application.properties、db/migration/scaffold、templates
├─ src/test/java/...            起動確認、KOIKI ArchUnit規則、Testcontainers
├─ .github/workflows/ci.yml     wrapper→./mvnw verify→container build
└─ docs/policy/                 本書等の方針文書
```

## 7. 取得経路の実証

### 7.1 取得・取込（2026-11-12、Claude cloud session）

| 確認 | 結果 |
| --- | --- |
| Release ZIPの未認証取得 | HTTP 200 |
| ZIP SHA-256 | `470a2fac…b27c`、§3の固定値と一致 |
| 同梱`prepare-koiki.ps1` | SHA-256 `9c1555e5…1f1d`。Framework repository main（`f6ff36d`）の`build-support/framework-distribution/prepare-koiki.ps1`と一致 |
| 空のlocal repositoryへの取込 | `-LocalArchive`で成功。14座標照合、JAR12／POM14、完了記録`.koiki-distributions/0.1.0-preview.1.json`生成 |

- Maven実行にはFramework repositoryのMaven Wrapperを一時的に使用した。本repositoryのWrapper追加後に、本repository単独で再実証する。

### 7.2 build（未実施）

土台の最小構成の実装後、同じ空のlocal repositoryから`./mvnw verify`・container buildまでを実証し、本節へ記録する。

## 8. 次の手順（案）

1. §5.2の識別子をOwner確認する。
2. 最小構成（§6）を実装し、§7.2の実証を行う。
3. Claude cloud用の個人環境（setup scriptでwrapper実行）を作成し、新しいsessionで自動準備を確認する。
4. 方式②：社内GHEのhost、CI用読取資格情報の形態、持ち込み時の配布元の書換え手順を、方式①のwrapperに合わせて整理する。
