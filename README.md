# koiki-java-scaffold

KOIKI-JavaWeb-FWを採用する業務アプリケーションの開発の土台です。
Framework `0.1.0-preview.2`、Spring MVC／Thymeleaf、local認証、JDBC Session、PostgreSQLを使用します。

初めてcloneして検証する場合は、[開発環境構築手順書](docs/development/local-setup-guide.md)を順に進めてください。
前提ツール、cloneする版、Frameworkの配置・参照先、社内取得設定、成功確認、停止・再開、トラブル対処を記載しています。
設計上の判断と検証履歴は[KOIKI Framework採用方針](docs/policy/koiki-framework-adoption-policy.md)を参照してください。

## アプリの実行方式は2択：JAR実行／コンテナ実行

**アプリは次のどちらか1つで起動します。DBは両方式ともPostgreSQLコンテナです。**

| 方式 | アプリ | DB | 用途 |
| --- | --- | --- | --- |
| A. JAR実行 | PC上のJavaでアプリJARを実行 | ComposeのPostgreSQL | 手軽な起動・開発時の確認 |
| B. コンテナ実行 | 同じアプリJARをimageへ格納して実行 | 同じComposeのPostgreSQL | コンテナ構成での動作確認 |

どちらも同じ`.env`・JAR・DB volume・登録ユーザーを使い、URLは`http://127.0.0.1:8080/`です。
方式別の設定ファイルは作りません。両方式は同時起動せず、片方を停止して切り替えます。
コンテナ方式でも、buildと初回ユーザー登録にはPC上のJDK 21が必要です。

## 前提とFrameworkの取得先

Git、JDK 21、PowerShell 7、Docker＋Compose v2が必要です。Maven 3.9.16はWrapperが取得します。
実案件ではFramework sourceと同一ZIPを社内へ持ち込み、同じtag名・asset名で登録した社内Releaseだけから取得します。
**最初のPrepare前に[手順書§3.1](docs/development/local-setup-guide.md#31-社内framework-releaseの取得先を設定する初回準備の前に必須)の社内配布設定とGitHub CLI認証を済ませてください。**
公開Scaffoldの初期設定はpublic検証用です。社内持込み時には社内用exampleへ置き換えてcommitします。
社内の取得・認証が失敗してもpublicへ切り替えません。

取得したZIPは`.koiki/downloads/`にcacheし、hash照合後に通常`~/.m2/repository`へ登録します。
Maven buildがFrameworkの必要なJARとSpring等の依存をアプリJARへ内包するため、実行先にFramework JARを別途配置する必要はありません。

## 共通準備と初回ユーザー登録

Dockerを起動し、このrepositoryのrootで実行します。既定portはDBが5432、アプリが8080です。

```powershell
# .envがなければsecretを自動生成。DB起動、Framework取込、verify・アプリJAR生成
pwsh ./tools/run-local.ps1 -Action Prepare

# 初回のみ。メールとpasswordを対話入力（passwordは非表示・確認入力あり）
pwsh ./tools/run-local.ps1 -Action CreateAdmin
```

既存`.env`は上書きしません。空欄入りの`.env.example`をcopy済みなら、先に値を設定してください。
初回passwordは15〜128文字かつUTF-8で72 byte以内です。固定passwordは同梱しません。
初回登録は空のローカルIdentityに限定し、既存ユーザーやpasswordを上書きしません。
`SCAFFOLD_ADMIN` roleと`SCAFFOLD:ADMIN` permissionを持ちますが、管理画面はまだありません。
本番のbootstrap・追加ユーザー管理・password回復は案件で別途設計します。

独自Maven repositoryを使う場合はPrepareへ`-LocalRepository <path>`、別の配布設定は`-ConfigurationFile <path>`を指定します。
取込・build・IDEで同じMaven repositoryを使ってください。

## 起動と停止

JAR方式はterminalを保持します。Ctrl+CでJavaを停止します。

```powershell
pwsh ./tools/run-local.ps1 -Action Start
```

コンテナ方式はimage buildと起動後、ログイン画面のHTTP200を確認してterminalへ戻ります。

```powershell
pwsh ./tools/run-local.ps1 -Action StartContainer
# アプリの状態・log
docker compose --profile app ps
docker compose --profile app logs --tail 100 app
# アプリcontainerのみ停止（DBは保持）
pwsh ./tools/run-local.ps1 -Action StopContainer
```

[http://127.0.0.1:8080/](http://127.0.0.1:8080/)で登録したメール・passwordを入力し、
ホームのPermissionsに`SCAFFOLD:ADMIN`が表示されることと、ログアウトを確認します。
両方式ともローカルHTTP用に起動時だけSecure Cookieをfalseにします。アプリJARの通常の安全な既定値は変更しません。

DBも停止する場合はアプリ停止後に`docker compose stop db`を実行します。
通常停止でvolumeを削除しません。`.env`も保持し、次回は同じユーザーを使います。
JAR再開前は`docker compose up -d --wait db`を実行します。StartContainerはDB起動も行います。

## 変更後のbuildと版の更新

StartもStartContainerもJavaのcompileは行いません。code変更後はアプリ停止→Prepare→選択した方式で起動します。
StartContainerは生成済みJARから毎回imageをbuildします。

Framework版の更新はParent版、配布版・ZIP／installer hash、レビュー済み同梱installerをまとめて更新します。
配布ZIPが同じbytesなら社内配布先への変更で版・hashは変えません。
sources／JavadocはIDE用に登録されます。アプリの依存へ追加する必要はありません。

## 案件への展開とCI

案件では`com.example.scaffold`、`koiki-java-scaffold`、DB名／userの`scaffold`、
アプリFlyway location／履歴table、image名を置き換えます。
`pom.xml`・`src/`・Compose・Dockerfile・CIを一緒に点検してください。
Frameworkの`org.koikifw`名前空間やFramework migrationを案件用にcopyしません。

CI定義は`docs/ci/ci.yml`に準備しており、まだ有効化していません。
社内runner、Release読取資格情報、依存・image取得経路を設定し、`.github/workflows/`へ配置後に検証します。

今回の整備は`chore/scaffold-preview2`ブランチで確認します。mainへの統合はローカルPCでの検証後に行います。
公開Scaffoldを手元へ取得する場合：

```powershell
git clone --branch chore/scaffold-preview2 https://github.com/zaziedlm/koiki-java-scaffold.git
Set-Location koiki-java-scaffold
```

実案件は、社内へ持ち込んだsourceと社内Framework Releaseを使ってください。
