# KOIKI Framework 採用方針（案）

状態：**DRAFT**（§5 決定事項・§5.2 識別子をOwner確認済み、§7 実証結果記録）
作成日：2026-11-12

## 1. 目的と位置づけ

`koiki-java-scaffold`は、KOIKI-JavaWeb-FW（以下Framework）を採用する業務アプリケーションの**開発の土台**である。
本書は、Frameworkの取得方式・版固定・環境別準備・土台の初期構成について、実装前に合意する方針をまとめる。

- Frameworkのsource repositoryは**参照のみ**とする。本repositoryからFrameworkへcommit・push・PRを行わない。
- 本repositoryはFramework source・Root Reactor・`relativePath`に依存せず、配布されたbinaryだけで構築できること。
- Framework側の検証・承認手続（Framework repositoryの`AGENTS.md`）は本repositoryには適用しない。本repositoryの規則は本repositoryの`AGENTS.md`／`CLAUDE.md`に置く。

## 現行追加方針：実案件では社内Framework Releaseのみを利用する

Owner指示により、Framework sourceと配布ZIPを社内へ持ち込み、同じtag名・asset名でReleaseへ登録する。
実案件はpublicを参照しない。以下の方式①の採用記述・publicでの検証は公開Scaffoldでの履歴であり、実案件の取得方式ではない。
社内版はGitHubCli方式・社内host／repositoryを設定する。ZIPが同じbytesなら版・hashは維持し、認証失敗時にpublicへfallbackしない。
初回Prepare前の必須操作は[構築手順書§3.1](../development/local-setup-guide.md)に従う。
公開Scaffoldの既定配布設定はpublic検証用に保持し、社内へ持ち込む際に社内用exampleから置き換えてcommitする。
社内の実host／repositoryは未提示のため未設定。実接続・SSOは社内環境で別途確認する。

設定例`tools/koiki-distribution.internal.properties.example`を追加し、取得wrapperは設定のsource／host／repositoryを使用する。
一括Prepareと個別取込は`-ConfigurationFile`も受け取る。配布同梱installerは変更しない。
ローカル代替ghで社内host／repositoryの受渡し・取得失敗時停止・GH_HOST復元・未置換設定の拒否を確認した。
社内用の代替設定を指定したPrepareは、既存の正規ZIP cacheから14座標／42 payload照合と全13 test・JAR生成に成功した。
社内からの実download成功や認証成立を示す結果ではない。
ignored Evidenceは`.koiki/verification/internal-distribution/`へ保持する。


## 2. Framework取得方式

Framework提供の**Release asset一式配布**（固定版ZIP）を採用する。GitHub Packages、開発者ごとのPATは使わない。

| 区分 | 配布元 | 認証 | 適用 |
| --- | --- | --- | --- |
| **方式①（採用）** | public `github.com/zaziedlm/KOIKI-JAVAWEB` のRelease | **不要** | 本repository（github.com上の土台） |
| 方式② | 社内GitHub Enterpriseへ持ち込んだprivate repositoryのRelease | 開発者：`gh auth login`（社内SSOのbrowser認証、PAT手作成不要）／CI：読取資格情報1つ | 社内GHEへ持ち込んだ土台・案件 |

### 2.1 配布物

ZIP `koiki-framework-<version>.zip`（Release tag `framework-<version>`）に次を同梱する。

- Maven repository配置のKOIKI artifact一式：14 POM（うち12はJAR付き）
- preview.2は上記にsources11 JAR・Javadoc5 JARを追加（計42 payload）
- `SHA256SUMS`、`manifest.json`（preview.2はschema2）
- 取込script `prepare-koiki.ps1`、`README.md`

全moduleは同一版。異なる配布物のmoduleを混在させない。

### 2.2 方式①の取得手順（概要）

1. ZIPをRelease URLから認証なしで取得する。
2. ZIP全体のSHA-256を本repositoryに記録した固定値と照合する。
3. 同梱の`prepare-koiki.ps1`を`-LocalArchive`で実行し、内部payloadのhash照合とMaven local repositoryへの登録を行う。
4. 通常どおり`./mvnw verify`でbuildする。

1〜3は本repositoryの取得wrapper（`tools/prepare-koiki-distribution.ps1`）で一括実行し、`gh`認証を不要にする。
`prepare-koiki.ps1`は本repositoryにレビュー済みcopyを保持し、取得したZIPのものと一致することも確認する。

### 2.3 方式②との差分

- wrapperは取得部分だけを切り替える（`gh release download`、GHE hostは環境変数`GH_HOST`で指定）。
  同梱scriptの`-Repository`は`owner/repo`形式のみ受け付けるため、host指定は`GH_HOST`で行う。
- CIは別private repositoryを`GITHUB_TOKEN`で読めないため、読取専用資格情報を1つSecretsへ置く（`GH_TOKEN`）。
  形態（GitHub App／machine user／fine-grained token）は社内GHE方針で決める。
- 版固定・hash照合・取込以降は方式①と同一。

## 3. 版固定

| 項目 | 現行値 | 管理場所 |
| --- | --- | --- |
| Framework版 | `0.1.0-preview.2` | `pom.xml`のParent版 |
| ZIP SHA-256 | `7858dc3719ed9f8f0fab164e6dfa2e6015893dd971b0d7bd6a48a37136aab08e` | `tools/koiki-distribution.properties` |
| 配布元 | `zaziedlm/KOIKI-JAVAWEB` | 同上 |

- preview.2の公開記録を根拠に固定し、今回の公開ZIP実取得でSHA-256と全manifest payloadを照合した。更新・検証結果は§7.3へ記録する。
- `0.1.0-preview.2`はPhase 5の正式／support付き提供ではない。preview版としての制約を受け入れて採用する。
- SNAPSHOTは追従しない。版更新はwrapper設定・Parent版・hashを同時に変更するPRで行う。

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

### 5.2 識別子（Owner確認済み）

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

### 5.4 ローカルKubernetesの追加方針（2026-11-19）

- Rancher DesktopのMoby／dockerd、Kubernetes 1.37.2を対象に、`deploy/kubernetes/base`と`overlays/local`へ定義を分離する。
- 共通Dockerfile・JARを再利用し、既存の`compose.yaml`・`run-local.ps1`・Java code・Framework配布物は変更しない。
- `tools/run-kubernetes-local.ps1`で専用context／namespace、DB／PVC、ランダム秘密値を扱う。Composeの`.env`は利用しない。
- JAR／ComposeとKubernetesはDBも含めて同時実行禁止。方式切替時は停止とbrowser Cookie消去を行い、専用Cookie名を追加しない。
- 初回ユーザー登録はPCから所有するDB port-forwardを介して既存Java runnerを実行し、loopback・空Identity制限を維持する。
- 共通定義は将来のEKS配備設計の出発点とする。ローカルDB・storage・HTTP設定はlocal overlayへ置き、EKS／本番用の設定とは扱わない。
- WindowsでPod実行・DB接続・初回登録・login／権限表示・停止／再開を確認済み。詳細は§7.8と[専用手順書](../development/kubernetes-local-guide.md)に記録する。

## 6. 土台の初期構成

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
├─ docs/ci/ci.yml              GitHub Actions定義。管理者が.github/workflows/へ配置
└─ docs/policy/                 本書等の方針文書
```

## 7. 取得経路の実証

§7.1〜7.2はpreview.1採用時の履歴であり、preview.2の結果とは分ける。

### 7.1 取得・取込（2026-11-12、Claude cloud session）

| 確認 | 結果 |
| --- | --- |
| Release ZIPの未認証取得 | HTTP 200 |
| ZIP SHA-256 | `470a2fac…b27c`、§3の固定値と一致 |
| 同梱`prepare-koiki.ps1` | SHA-256 `9c1555e5…1f1d`。Framework repository main（`f6ff36d`）の`build-support/framework-distribution/prepare-koiki.ps1`と一致 |
| 空のlocal repositoryへの取込 | `-LocalArchive`で成功。14座標照合、JAR12／POM14、完了記録`.koiki-distributions/0.1.0-preview.1.json`生成 |

- 初回はFramework repositoryのMaven Wrapperを一時的に使用した。§7.2で本repositoryの取得wrapper・Wrapperだけで再実証した。

### 7.2 空のlocal repositoryからbuild・実行まで（2026-11-12、Claude cloud session）

新規の空local Maven repository（KOIKI・third-party依存とも未取得）を`-Dmaven.repo.local`／`-LocalRepository`で指定して実施した。

| 段階 | 結果 |
| --- | --- |
| 取得・取込（`tools/prepare-koiki-distribution.ps1`） | public Releaseから未認証取得、ZIP・同梱script SHA-256一致、14座標登録 |
| `./mvnw verify` | BUILD SUCCESS。test 8件PASS（Architecture Rules 2、PostgreSQL上のアプリケーション6）。NullAway有効でcompile |
| アプリケーションtestの内容 | KOIKI migration3件適用、未ログイン`/`→`/login`、未一致route拒否（401／403）、CSRFなしlogin拒否、誤password拒否、login→JDBC Session1件→logoutで0件 |
| 実行可能JAR | Compose `db`＋`java -jar`で起動。`/`→302 `/login`、`/login` 200、未一致route 401。標準Security Header、Session Cookie `Secure; HttpOnly; SameSite=Lax` |
| container | `docker build`成功（base imageはdigest固定）。Compose `app` profileで起動し同じ応答。実行user `10001:10001`、SIGTERMでgraceful shutdown完了（exit 143、OOMなし） |

観測した注意点：

- Flywayがアプリ側locationについて`No migrations found`をWARN出力する。アプリmigration未作成の土台では想定どおり。
- Spring Securityが`UserDetailsService`未使用のWARNを出力する。KOIKIのAuthenticationProvider構成による想定内の出力。
- `koiki-starter-web-mvc`の共通fragment `koiki/fragments :: header`はReference固有のmenu（経費申請等）を含むため、本土台では`head`のみ利用し、headerは自前で持つ。Frameworkへのfeedback候補。

Claude cloud session固有の事象（アプリの欠陥ではない）：

- Docker daemonが未起動だったため手動で起動した。
- Docker Hubの匿名pull制限（HTTP 429）に一時的に当たった。test中はECR Public mirrorから取得したPostgreSQL imageを同名tagで利用し、Testcontainersの補助container（Ryuk）を`TESTCONTAINERS_RYUK_DISABLED=true`で無効化した。時間をおいてDocker Hubから取得でき、container buildは正規のimage名・digestで実施した。
- ECR Publicのlayer配信host（CloudFront）はsessionのnetwork policyで拒否された。

### 7.3 preview.2への配布版更新（2026-11-17、Codex Cloud session）

Scaffold開発ブランチの`a4120a3`を起点に、Parentと配布設定を`0.1.0-preview.2`へ更新した。
`tools/prepare-koiki.ps1`は公開ZIP同梱版をレビューし、byte単位でそのまま差し替えた。
取得wrapper・アプリケーションcode・test・container設定・CI配置は変更していない。

| 確認 | 結果 |
| --- | --- |
| 公開ZIP | [framework-0.1.0-preview.2](https://github.com/zaziedlm/KOIKI-JAVAWEB/releases/tag/framework-0.1.0-preview.2)から未認証取得。SHA-256は§3と一致 |
| 配布元source | manifestの`sourceCommit=557fbfc59ddbf1275b1ff53c95b68d9e21739dfa`、`sourceDirty=false`、schema2 |
| ZIP内容 | manifest記載44 fileのsize／SHA-256一致（Maven payload42＋installer／README） |
| installer | SHA-256 `8ed85c91d1ab55027c374f70fdd67aa307de0c26c4f78b6c79d61ba2cb4bed4a`。公開ZIP同梱版と完全一致 |
| 取得wrapper→登録 | 実取得済みZIP cacheから、新規専用Maven repositoryへ14座標／42 payload登録成功。全42 hash・schema2完了marker・lock残留なしを別途照合 |
| `./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local=<専用repository> verify` | BUILD SUCCESS。Architecture Rules 2件＋実PostgreSQL上のアプリケーション6件、計8件PASS、failure／error／skip 0。NullAway有効でcompile、実行可能JAR生成成功 |
| 実行可能JAR内容 | KOIKI依存8 JARすべてpreview.2。sources／Javadoc JAR混入0 |
| test用container | 通常Testcontainers実行（Ryuk有効）。終了後`docker ps -a`は空 |

環境：JDK21.0.12.1、Maven Wrapper3.9.16、PowerShell7.5.4（公式配布hash照合）、Docker28.4.0。
初回PowerShell起動はuser cacheのread-only制約で停止したため、XDG cache／config／dataを検証専用directoryへ指定した。
初回Maven登録はCentralの直接名前解決に失敗し、完了markerは作られなかった。
既存の環境用JDK／trustStore／proxy設定を適用して同じZIP・登録先で再実行し、上記の登録とbuildが成功した。
これらは環境設定だけの対応で、配布installerやアプリへの回避実装は加えていない。

このsession内のignored資材は`.koiki/downloads/`、`.koiki/verification/`、
`.koiki/prepare-preview2.log`、`.koiki/verify-preview2.log`と`target/surefire-reports/`に保持する。
ZIP・Maven cache・raw logはGitに含めない。独自`CookieSerializer`の見直し、IDE実表示、JAR／containerでの再起動確認、
GitHub Actions有効化・実行、社内GHE、main統合は今回の結果に含めず、後続作業とする。

### 7.4 preview.2標準CookieSerializerへの移行（2026-11-17、Codex Cloud session）

preview.1の構成順序問題を補うために追加していた`scaffoldCookieSerializer()`を削除し、
不要な`@EnableConfigurationProperties(ServerProperties.class)`とimportを除去した。
アプリのSecurityFilterChain・route・CSRF・Security Header・既存logout customizer呼出しは維持する。
Cookie属性の設定は`server.servlet.session.cookie.*`を用い、Spring Bootのserializer生成に任せる。
Framework sourceの変更や独自serializerの代替実装は行っていない。

既存アプリtestは組込みserverを起動する`RANDOM_PORT`へ変更し、MockMvc経由の既存6件を維持した。
これによりMock ServletContextだけの構成と異なり、Bootの実行可能JARと同じembedded-server用Cookie構成を評価する。
追加のHTTP test 1件は別の組込みserverで実通信する。

| 確認 | 結果 |
| --- | --- |
| Bean | アプリ独自宣言なしでBootの`cookieSerializer`だけが登録され、アプリのlogout customizer注入も成立 |
| ローカルHTTP設定 | 既存login testで`secure=false`の反映、HttpOnly・SameSite=Laxを確認 |
| 実HTTP login | Cookie名`SCAFFOLDSESSION`・path／context-path `/scaffold`を指定し、実HTMLのCSRF tokenでlogin、認証済み画面とJDBC Session1件を確認 |
| Cookie既定属性 | 発行・削除CookieともSecure・HttpOnly・SameSite=Lax、指定した名前・pathを確認 |
| 実HTTP logout | Cookieを空値・Max-Age=0で削除、DB Session0件、旧Cookieを再送してもloginへredirect |
| 全体verify | `./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local=<§7.3の専用repository> verify`成功。Architecture2＋既存アプリ6＋新規HTTP1＝9件、failure／error／skip0。実行可能JAR生成成功 |

新規HTTP testはloopback HTTPへCookie headerを明示的に送る。
Secure属性の検査と認証動作を対象とし、実ブラウザのSecure Cookie送信規則やTLS／proxyの検証とはしない。
test用利用者は専用PostgreSQLへのfixtureであり、初回ユーザー作成手段を製品へ追加していない。
container設定は変更せず、image再build・実ブラウザ・packaged JAR単独起動は今回の確認に含めない。

READMEへ、既定Secure=trueを維持し、ローカルHTTP確認時だけ起動引数／container環境変数でfalseを指定する方法を追記した。
検証logはignoredの`.koiki/verify-boot-cookie.log`、XMLは`target/surefire-reports/`へ保持する。
独自CookieSerializer見直しは本節で完了とし、§7.3の後続事項記述は更新前の履歴として読む。
CI有効化・IDE・案件受入・main統合は後続とする。

### 7.5 ローカル初回管理ユーザー登録とJAR起動（2026-11-17、Codex Cloud session）

Ownerの「まずScaffoldをさくっと動かす」指示により、`tools/run-local.ps1`へ
`Prepare`／`CreateAdmin`／`Start`の3段階を追加した。

- Prepare：存在しない場合だけランダムsecret入り`.env`を作成（Unixでは0600）、Compose DB起動待ち、配布物取込、Maven verify。
- CreateAdmin：メール・password（非表示・確認入力）を受け、同じJARを明示profile／flag付き非Webで起動する。
  passwordはcommand lineや環境変数へ置かず、子Javaの標準入力で渡す。通常のWeb起動ではrunnerは登録されない。
- Start：同じJARを127.0.0.1:8080で起動し、ローカルHTTP用にだけSecure Cookieをfalseへ上書きする。

初期登録はpreview.2のIdentity schemaに依存するアプリ所有のローカルprovisioningである。
公開`IdentityAdministration`が要求する既存認証主体を偽造せず、JDBCで初期データを登録する。
Framework内部Entity／Repository・migration SQLのcopyは使わず、FrameworkのPasswordEncoderと公開BusinessAuditRecorderを利用する。
ユーザーtableのlock下で空のIdentityだけを対象に、ACTIVE user／hash化credential／`SCAFFOLD_ADMIN` role／
`SCAFFOLD:ADMIN` permissionと割当を同じtransactionで作る。
監査は`SCAFFOLD_BOOTSTRAP`／`CREATE_INITIAL_ADMIN`、actorはANONYMOUS、subjectは生成user UUIDとする。
失敗時はAuditも含めrollbackし、既存ユーザーは一切更新しない。
入力はASCIIメール、NFC正規化後15〜128文字・bcrypt用UTF-8上限72 byteのpasswordに限定する。
bootstrapの接続先は明示port付きloopback PostgreSQL URLに限定する。

管理roleは今後のアプリ機能向けの印であり、Framework管理機能や全画面への特権アクセスを提供しない。
本番bootstrap、追加ユーザー管理、password reset、漏洩password検査など通常のIdentity管理経路を代替しない。
Framework更新時にschema・PasswordEncoder・password policyを再確認する。

| 確認 | 結果 |
| --- | --- |
| `Prepare`実行 | `.env`自動生成、Compose DB healthy、配布取込、verify／JAR生成成功。Maven repositoryは§7.3と同じ専用pathを指定 |
| 自動test | 全13件PASS（既存9＋初回登録4）、failure／error／skip0 |
| 初回登録4件 | 作成・hash・Audit・管理権限付きlogin、既存Identity拒否、短いpassword拒否、Audit失敗時の全登録rollback |
| CreateAdmin実行 | 同じpackaged JARの非Web起動で初回登録成功し、process正常終了 |
| Start実行 | 同じpackaged JAR＋Compose PostgreSQL17.11、loopback port8080で起動 |
| 実HTTP操作 | login→ホームにSCAFFOLD:ADMIN表示→対象userのDB Session1件→logoutで0件→旧Cookie再送拒否。初回登録Audit1件 |
| 再登録 | CreateAdminの再実行が非zeroで終了。ユーザー1件・初回Audit1件を維持し、再login／logoutも成立 |

実HTTPの初回確認ではlogout後の匿名CSRF用Sessionも総数へ含めたためassertionが失敗した。
対象管理userのprincipal_nameで数えるよう検証harnessだけを訂正し、上記の1→0と旧Cookie拒否を確認した。
先行して一時harnessのscript path誤りも訂正した。いずれも製品の認証処理を緩める変更はしていない。

ignored Evidence：`.koiki/local-prepare.log`、`local-create-admin.log`、`local-create-admin-repeat.log`、
`local-app.log`、`.koiki/verification/local-journey.json`、`target/surefire-reports/`。
今回の手動確認用DBとJARは稼働保持。検証用メールは`local-admin@example.test`、ランダムpasswordは
`.koiki/verification/local-admin-password`（0600）にのみ保持し、Gitや本書へ値を記録しない。
`.env`もGit対象外である。停止はJava終了と`docker compose stop db`、volume削除・再seedは実施していない。
このCloud検証はWindows／実ブラウザの操作確認を含まない。

### 7.6 JAR／コンテナ起動の選択と手順整理（2026-11-17、Codex Cloud session）

Owner指示により共通Prepare／CreateAdminの後に、既存Startか新規StartContainerを選ぶ構成へ整理した。
既存Dockerfile・compose.yaml・.env・DB volumeを再利用し、設定ファイルは増やしていない。
Compose appのcommandにローカルHTTP用`secure=false`だけを追加し、JAR内の既定は維持する。
StartContainerはport競合確認、既存JARからのimage build、Compose起動、login HTTP200待ちを行う。
StopContainerはappだけを停止し、DBとvolumeを保持する。Startは稼働中app containerとの競合を拒否する。

| 検証 | 結果 |
| --- | --- |
| 全体verify | 13件PASS、failure／error／skip0、JAR生成成功 |
| image build | 既存digest固定Temurin21からDockerfile build成功。非root user10001:10001 |
| JAR→コンテナ | 既存JARのport8080稼働中はStartContainerが拒否。今回所有のJavaをSIGTERMで停止後、StartContainerで起動成功 |
| 実HTTP | 同じDBの既存管理ユーザーでlogin、SCAFFOLD:ADMIN表示、対象user Session1→logoutで0、旧Cookie拒否 |
| 逆向きの競合保護 | app container稼働中のStartが非zeroで拒否。別Javaを起動しない |
| 停止 | StopContainerでexit143／OOM=false。DBはrunning／healthy、既存volumeを保持 |
| 再起動 | StartContainerでimage再build・起動・HTTP200、同じユーザーで再login／logout成立。user1件／初回Audit1件を維持、再seedなし |
| 最終状態 | app／DB稼働保持。app restart0／OOMなし、volumeは既存koiki-java-scaffold_db-data |

初回buildはCloudのuser home read-onlyによるDocker buildxのcache作成拒否で停止した。
検証processのDOCKER_CONFIGをignoredの専用directoryへ指定して解消し、Dockerfileや固定base imageは変更していない。
Windows／実browser／社内GitHub接続／AWS／本番運用は今回の検証対象外。

Evidenceはignoredの`.koiki/container-start.log`、`container-restart.log`、`verify-container-pattern.log`、
`.koiki/verification/container-journey.json`、`container-restart-journey.json`、`jar-conflict.log`、`target/surefire-reports/`。
READMEは入口・操作早見表へ整理し、詳細手順は構築手順書の「共通準備→A JAR／Bコンテナ→停止・再開・切替」へ統一した。
§7.5のJAR稼働保持は当時の状態とし、現在は上記コンテナを稼働保持する。

### 7.7 WindowsローカルでのJAR／コンテナ検証（2026-11-19）

利用者がWindows上のVSCodeのPowerShell terminalから本repositoryのscriptを実行し、
実browserでアプリを確認した。対象は`chore/scaffold-preview2`、
commit `1702eb82fe6783e1b1072b17e4fd28a1cd831bdf`、Framework `0.1.0-preview.2`。
過去の§7.3〜7.6はCloudでの実施時点の記録として維持し、Windowsの結果は本節に分ける。

| 確認 | 結果・根拠 |
| --- | --- |
| 環境 | Windows／VSCode、Temurin JDK21.0.13、PowerShell7.6.10、Rancher DesktopのDocker Engine29.5.3（Linux container）、Compose5.1.4 |
| Prepare | public配布設定で実行し、利用者提示logでBUILD SUCCESSと準備完了を確認。独自Maven repository指定なし |
| 自動test | ローカルSurefire XMLを読取確認。13件成功、failure／error／skip0 |
| 初回登録 | 利用者提示logで同じpackaged JARからのCreateAdmin成功・正常終了を確認。Framework migration3件適用 |
| JAR | 起動logでport8080・アプリ起動完了。利用者が実browserでlogin・SCAFFOLD:ADMIN表示・logoutを確認し、再実行も確認 |
| コンテナ | 利用者がStartContainer成功を確認。停止・再起動手順の案内後、同じユーザーで再login成功と報告 |

初回Prepare前のCompose状態確認は`.env`未作成のためDB password不足で失敗したが、Prepare実行で準備が完了した。
アプリmigration未作成と独自AuthenticationProvider構成による想定内WARNを観測した。
手順と確認範囲の詳細は[構築手順書§11.1](../development/local-setup-guide.md#111-windowsローカルでの初回検証2026-11-19)を参照。

今回のWindows検証ではDB Session／Audit件数、container非root・終了code・OOM、Cookie属性、
logout後のホーム再アクセスを個別確認していない。コンテナの権限表示・logoutも個別結果の報告はない。
Cloudで得たこれらの結果をWindowsの証拠として流用しない。
VSCodeのF5デバッグ・sources／Javadoc実表示、社内GitHub実接続、CI、AWS、本番運用は対象外。
secret・メール・password・Cookie値は記録しない。

### 7.8 Rancher DesktopでのKubernetesローカル検証（2026-11-19）

`feat/local-kubernetes`で追加した設定と専用scriptを、利用者がWindowsのVSCode terminalで実行した。
対象はRancher Desktop Moby／Docker Engine29.5.3、Kubernetes1.37.2、Temurin JDK21.0.13、PowerShell7.6.10。
Framework版は0.1.0-preview.2。既存のCompose／JAR検証とは分けて記録する。

| 確認 | 結果・根拠 |
| --- | --- |
| オフライン確認 | PowerShell構文解析、guard確認11件、Kustomize展開、git diff --check成功 |
| Prepare | 利用者提示logでFramework14座標／42 payload照合、verifyのtest13件成功・failure／error／skip0、Docker build成功 |
| DB準備 | 専用namespace・Secret・StatefulSetを作成。DB起動待ち完了、local-path PVCがBound |
| Pod実行 | 対象Podのprefix付きlogでport8080・アプリ起動完了とscaffold-dbへの接続を確認。利用者が18080経由でlogin・SCAFFOLD:ADMIN表示を確認 |
| 停止・再開 | 利用者提示Statusでapp／DBとも0/0・Podなし・PVC保持を確認。利用者が再実行後の動作を確認 |
| 初回登録の表示修正 | 初版の表示不足を受け、子Javaの標準出力・標準エラーの明示取得と成功確認を追加。既存DBでは再登録拒否の例外・script失敗表示を確認 |
| 空DBでの成功確認 | 利用者が専用PVCを削除・再作成し、新VOLUMEを確認。Framework migration3件適用と修正版CreateAdminの成功メッセージ2つをlogで確認。その後のlogin・権限表示も利用者が確認 |

DB再作成ではComposeのvolume・KubernetesのSecret・ローカル秘密値ファイルを削除していない。
通常停止とデータ消去を区別し、例外的な再作成手順を[専用手順書§7.1](../development/kubernetes-local-guide.md#71-検証用dbを空にして初回登録をやり直す場合だけ)へ記載した。
同時実行禁止は利用者の運用制約でもあり、既存Compose用scriptによる逆方向の自動検出は追加していない。

Kubernetesでのlogout・logout後の再アクセス、Cookie属性、DB Session／Audit件数、containerの実行user・終了code・OOM、
異常系guardの実クラスタ確認は今回の確認結果に含めない。EKS／AWS実接続・本番運用は対象外。
メール・password・Cookie値・Secret内容は記録しない。

## 8. 次の手順（案）

1. **初回ユーザー**：ローカル開発は§7.5のCreateAdminで登録・起動できる。本番のbootstrap・追加ユーザー管理・password回復は別途案件判断。
2. **container imageの取得元**：Docker Hubの匿名pull制限を考慮し、案件・CIでmirror（ECR Public、社内registry等）を使うかを決める。`Dockerfile`の`JAVA_RUNTIME_IMAGE`は同じdigestでの差し替えを想定している。
3. **Claude cloud用の個人環境**：setup scriptで取得wrapperの実行とDocker daemonの起動を行い、新しいsessionで自動準備を確認する。
4. **方式②**：社内GHEのhost、CI用読取資格情報の形態、持ち込み時の配布元の書換え手順を、方式①のwrapper（`-Source GitHubCli`）に合わせて試行・整理する。
5. **GitHub Actions**：Claude GitHub Appはworkflow fileをpushできない（`workflows`権限なし）。管理者が`docs/ci/ci.yml`を`.github/workflows/`へ配置した後、GitHub-hosted runnerでの取得→verify→container buildを確認する。
