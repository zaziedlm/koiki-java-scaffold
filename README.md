# koiki-java-scaffold

KOIKI-JavaWeb-FW（以下Framework）を採用するSpring MVCアプリケーションの**開発の土台**です。
案件ではこのrepositoryを起点に、識別子を置き換えて業務機能を追加します。

- Framework：固定版 `0.1.0-preview.1`（public GitHub ReleaseのZIP、認証不要）
- 構成：Spring MVC＋Thymeleaf、local認証（email＋password）、JDBC Session、PostgreSQL、Flyway
- 方針：[KOIKI Framework採用方針](docs/policy/koiki-framework-adoption-policy.md)

## 前提ツール

| ツール | 用途 |
| --- | --- |
| JDK 21 | build・実行（Framework baselineはJava 21） |
| PowerShell 7（`pwsh`） | Framework配布物の取得・取込 |
| Docker（Desktop等） | Testcontainersによるtest、Compose、container build |

Maven本体は不要です（Maven Wrapperが3.9.16を取得します）。

## 1. Framework配布物を取り込む（初回・版更新時）

```bash
pwsh ./tools/prepare-koiki-distribution.ps1
```

`tools/koiki-distribution.properties`に固定した版のRelease ZIPを取得し、ZIPと同梱scriptのSHA-256を照合したうえで、
同梱の`prepare-koiki.ps1`（レビュー済みcopyを`tools/`に保持）により`~/.m2/repository`へ14座標を登録します。
ZIPは`.koiki/downloads/`にcacheされます。

社内GitHub Enterprise等のprivate repositoryから取得する場合（方式②）は、`gh auth login`のうえで次のようにします。

```bash
GH_HOST=<GHEのhost> pwsh ./tools/prepare-koiki-distribution.ps1 -Source GitHubCli -Repository <owner>/<repo>
```

## 2. build・test

```bash
./mvnw verify
```

Testcontainersが`postgres:17-alpine`を起動します（Dockerが必要）。
NullAway、KOIKI Architecture Rules（ArchUnit）、ログイン／ログアウトを含むアプリケーションtestが実行されます。

## 3. ローカル実行

```bash
cp .env.example .env   # 値を設定する。.envはcommitしない
```

### 実行可能JAR

```bash
docker compose up -d db
```

```bash
set -a; . ./.env; set +a
java -jar target/koiki-java-scaffold.jar
```

### container

```bash
./mvnw verify
```

```bash
docker compose --profile app up -d --build
```

いずれも`http://localhost:8080/`で起動し、未ログイン時は`/login`へ移動します。

### ログインユーザーについて

Frameworkは組込みユーザー・固定passwordを提供しません。またFrameworkのIdentity管理API（`IdentityAdministration`）は
ログイン済み管理者の操作を前提とするため、初回ユーザーの用意方法（管理用経路、OIDC、運用手順等）は案件で決めます。
本土台では未決定事項として残しています（[採用方針](docs/policy/koiki-framework-adoption-policy.md)§8）。

## 4. 識別子の置き換え

| 項目 | 土台の値 |
| --- | --- |
| groupId／base package | `com.example.scaffold` |
| artifactId／`spring.application.name`／JAR名 | `koiki-java-scaffold` |
| main class | `ScaffoldApplication` |
| DB名／user | `scaffold` |
| Flyway location／履歴table | `db/migration/scaffold`／`scaffold_flyway_history` |
| container image | `koiki-java-scaffold:local` |

`pom.xml`、`src/`のpackage、`application.properties`、`compose.yaml`、`Dockerfile`、`.dockerignore`、CI定義をまとめて置き換えます。

## 5. 構成

```
pom.xml                       koiki-parentをparentとする（<relativePath/>）
tools/                        Framework配布物の取得・照合・取込
src/main/java/com/example/scaffold/
  ScaffoldApplication.java    起動class
  ScaffoldSecurityConfiguration.java  アプリ所有のSecurityFilterChain（対象routeを明示、CSRF有効）
  HomeController.java         ログイン後の画面
src/main/resources/
  application.properties      DB・Flyway・local認証の設定（secretは環境変数）
  db/migration/scaffold/      アプリ所有のmigration（KOIKIのmigrationは別履歴で自動実行）
src/test/java/                Architecture Rules、PostgreSQL上のアプリケーションtest
compose.yaml / Dockerfile     ローカルDB、実行専用container image
docs/ci/ci.yml                GitHub Actions定義（.github/workflows/へ手動配置、§7）
```

## 6. 版の更新

`pom.xml`の`koiki-parent`版と`tools/koiki-distribution.properties`（版、ZIP SHA-256、同梱script SHA-256）を
同じ変更で更新します。同梱scriptが変わった場合は`tools/prepare-koiki.ps1`もレビューのうえ差し替えます。

## 7. CIの有効化

GitHub Actionsの定義は`docs/ci/ci.yml`にあります（取込→`./mvnw verify`→container build、資格情報不要）。
Claude GitHub Appにはworkflow fileを作成する権限がないため、repositoryの管理者が次のように配置してcommitします。

```bash
mkdir -p .github/workflows && git mv docs/ci/ci.yml .github/workflows/ci.yml
```
