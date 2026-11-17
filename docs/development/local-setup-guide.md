# Framework取得からローカル起動までの開発環境構築手順書

版：初版 v0.2（2026-11-17）

対象：KOIKI Framework `0.1.0-preview.2` を使う Scaffold

> **アプリの実行方式は、次の2つからどちらか1つを選びます。**
>
> **A. JAR実行**：PC上のJavaでアプリJARを直接起動します。
>
> **B. コンテナ実行**：アプリJARを格納したDockerコンテナを起動します。
>
> **DBは、どちらを選んでもDocker ComposeのPostgreSQLコンテナを使います。**
> アプリの2方式を同時には起動しません。共通準備の後、§6でAかBを選択してください。

## 1. この手順で確認すること

初めてGit cloneしたPCで、共通のFramework取得・登録、アプリbuild、初回管理ユーザー登録を行い、
その後「PC上のJAR」「アプリcontainer」のどちらかで起動します。
ログイン・ログアウト、停止・再開を順に確認します。WindowsのPowerShell 7を主な操作環境とします。
IDEがなくても実行できます。実行するコマンドは、特記がなければScaffold repositoryのrootが作業directoryです。

この手順とpreview.2対応・初回登録scriptは`chore/scaffold-preview2`ブランチにまとめます。
検証するbranchは§3のとおりです。mainへの統合はローカルPCでの検証後に行います。
元の開発branchや初期mainだけでは本手順のファイルが揃いません。

Cloud Linuxでは後述の13 testと、packaged JAR＋Compose DBでのHTTP login／logoutまで確認済みです。
Windowsでの本手順の通し実行・実browser操作は未確認であり、利用者の初回検証結果を追記する前提の初版です。
社内GitHub実接続は未検証です。設定解決・CLIへのhost引渡し・失敗時の停止はローカル代替CLIで確認済みです。
社内設定を指定したPrepareについても、既存の検証済みZIP cacheからの42 payload登録と13 test成功を確認済みです。
コンテナ方式もCloud Linuxでimage build・非root実行・管理ユーザーのHTTP login／logout・停止／再起動後の再loginを確認済みです。
StopContainerでアプリだけが終了（exit143、OOMなし）し、同じDB／volumeが保持されることを確認しました。
CI有効化、AWS配置、本番運用は本書の対象外です。

### 1.1 作業順序と起動方式

| 段階 | 両方式共通／選択 | 実施する内容 |
| --- | --- | --- |
| §2〜3 | 共通 | ツール確認、clone、社内Framework配布先・認証設定 |
| §4 | 共通 | Prepare：DB起動・Framework取込・verify・アプリJAR生成 |
| §5 | 初回だけ共通 | CreateAdmin：同じDBへ初期ユーザー登録 |
| §6 A | 選択 | Start：PC上のJavaでJARを実行 |
| §6 B | 選択 | StartContainer：同じJARをimageへ格納して実行 |
| §7 | 方式に応じて | 停止、再開、方式の切替、変更後の再build |

両方式は同時起動せず、片方を停止してから切り替えます。URLは共通で`http://127.0.0.1:8080/`です。
本手順の既定構成では`.env`、`compose.yaml`、アプリJAR、DB volumeは共通で、方式別に設定ファイルを複製しません。
管理ユーザーも共通なので、方式を切り替えてもCreateAdminをやり直す必要はありません。
コンテナ方式でも、PrepareとCreateAdminにはPC上のJDK 21を使います。

## 2. 配置・依存関係と前提ツール

### 2.1 Framework JARを置く場所

```text
GitHub Releaseの固定版ZIP
  ↓ 取得し、固定SHA-256と照合
<repository>/.koiki/downloads/koiki-framework-0.1.0-preview.2.zip
  ↓ 同梱installerで内部hash照合・Maven登録
<user profile>/.m2/repository/org/koikifw/<artifact>/0.1.0-preview.2/
  ↓ MavenがParent・BOM・必要な依存を参照してアプリをbuild
<repository>/target/koiki-java-scaffold.jar
  ├ BOOT-INF/classes/   アプリのcode・画面・設定
  └ BOOT-INF/lib/       必要なFramework／Spring等の依存JAR
  ↓ java -jar（Start scriptが実行）
A: PC上のJava process → 127.0.0.1:5432のPostgreSQL container
または
B: Dockerfileで同じJARをimageへ格納
   アプリcontainer → db:5432の同じPostgreSQL container
```

Windowsの既定登録先は通常`C:\Users\<ユーザー名>\.m2\repository`です。
Linux／macOSでは通常`~/.m2/repository`です。アプリの`lib/`へ手でJARをcopyしません。

preview.2は14座標／42 payload（14 POM・本体12 JAR・sources11 JAR・Javadoc5 JAR）を登録します。
全座標の登録と、全座標へのアプリ依存は別です。アプリが必要とするJARだけが実行可能JARへ入ります。
sources／JavadocはIDE参照用で、実行時の依存へ追加しません。

Frameworkは固定版ZIPから取得し、Spring等の第三者依存・Maven pluginは通常Maven Centralから取得します。
実案件ではFramework sourceも同じtag・同じasset名のRelease ZIPも社内へ持ち込み、社内Releaseだけから取得します。
publicのFramework repositoryへ接続せず、社内取得の失敗時にもpublicへ切り替えません。
社内認証にはGitHub CLIを使います。第三者依存・container imageの取得経路は別途社内network／mirror方針に従ってください。

実行先ではJava実行環境・アプリJAR・DB接続等の設定を使います。
Framework JARやMaven repositoryを別途配置する必要はありません。
Framework更新時にはアプリを再buildしてください。Maven repositoryだけを更新しても、生成済みアプリJARは変わりません。

### 2.2 前提ツールの確認

JDK 21、Git、GitHub CLI（gh）、PowerShell 7、Docker Desktop等のDocker＋Compose v2を用意し、Dockerを起動します。
WindowsではLinux containerを利用できる状態にします。Maven本体はWrapperが取得するため別途install不要です。

PowerShell 7で確認します（Windows PowerShell 5.1ではありません）。

```powershell
git --version
gh --version
$PSVersionTable.PSVersion
java -version
javac -version
Get-Command java
$env:JAVA_HOME
docker version
docker compose version
```

確認点：PowerShellは7系、`java`・`javac`は21系、DockerはClientだけでなくServerも応答すること。
`JAVA_HOME`を設定している場合はJDK 21を指し、PATH上のJavaとも揃えます。
WrapperはMaven 3.9.16を取得します。初回は社内GitHub Releaseと、Maven／container依存の配布経路への通信が必要です。
社内proxy／証明書の設定が必要なPCでは、社内手順に従って先に設定してください。

既定portはDBが5432、アプリが8080です。別のPostgreSQLやアプリが使っていないことも確認します。

```powershell
Get-NetTCPConnection -State Listen -LocalPort 5432,8080 -ErrorAction SilentlyContinue
```

このport確認はWindows用です。表示されたprocessがある場合は用途を確認し、既存のサービスを無断で停止しないでください。

## 3. Git cloneして確認対象の版を選ぶ

書込み可能な作業用親directoryへ移動し、次を実行します。
公開Scaffoldのローカル確認用sourceは、次のコマンドで取得できます。

```powershell
git clone --branch chore/scaffold-preview2 https://github.com/zaziedlm/koiki-java-scaffold.git
Set-Location koiki-java-scaffold
```

実案件は社内へ持ち込んだrepositoryを使います。上のcloneと次のcloneはどちらか片方だけを実行します。
次のURLは実在する社内ScaffoldのURLへ置き換え、今回の成果を含むbranch名を指定します。
`chore/scaffold-preview2`は同じ名前で社内へ持ち込んだ場合の例です。

```powershell
$scaffoldRepository = 'https://REPLACE_INTERNAL_HOST/REPLACE_ORG/koiki-java-scaffold.git'
git clone $scaffoldRepository koiki-java-scaffold
Set-Location koiki-java-scaffold
$scaffoldRef = 'chore/scaffold-preview2'
git fetch origin $scaffoldRef
git switch --detach FETCH_HEAD
```

上の操作は、確認するsource断面を固定するためdetached HEADでcheckoutします。
そのまま動作確認できます。編集して成果を残す場合は、別途作業branchを作成します。
Gitのコマンドが失敗した場合は先へ進まず、branch／tag名と公開状況を確認してください。

```powershell
git status --short --branch
git rev-parse HEAD
Test-Path ./tools/run-local.ps1
Test-Path ./docs/development/local-setup-guide.md
Get-Content ./tools/koiki-distribution.properties
Select-String -Path ./pom.xml -Pattern '0.1.0-preview.2'
```

両方の`Test-Path`が`True`であること、配布設定とParent版がpreview.2であることを確認します。
`git rev-parse HEAD`の結果を検証記録へ残します。

### 3.1 社内Framework Releaseの取得先を設定する（初回準備の前に必須）

この節は実案件の社内利用で必須です。公開Scaffold自体の社外での動作検証では、既存public配布設定を使えますが、
その設定を実案件へ持ち込まないでください。社内実接続の確認は社内環境で別途行います。

社内へ持ち込む担当者が、Framework repositoryのReleaseに次を用意します。

- tag名：`framework-0.1.0-preview.2`
- asset名：`koiki-framework-0.1.0-preview.2.zip`
- ZIPは公開元から受領したものをbyte単位でそのまま登録する。再build・再圧縮・内容編集をしない。
- ZIPのSHA-256が§4.1と一致することを確認する。取得先変更だけなら版・ZIP hash・installer hashは変更しない。

Scaffoldの配布設定を社内用へ切り替えます。

```powershell
Copy-Item ./tools/koiki-distribution.internal.properties.example ./tools/koiki-distribution.properties
```

`tools/koiki-distribution.properties`をeditorで開き、次の2値を実在する値へ置き換えます。

```properties
koiki.distribution.source=GitHubCli
koiki.distribution.host=REPLACE_WITH_INTERNAL_GITHUB_HOST
koiki.distribution.repository=REPLACE_ORG/REPLACE_FRAMEWORK_REPO
```

hostは`https://`を含まないhost名、repositoryは`組織名/リポ名`です。
sourceはGitHubCliのままにします。社内版のこの設定をreview・commitし、各開発者が同じ取得先を使うようにします。
資格情報はこのファイルへ記録しません。public用の初期設定は公開Scaffold自体の検証用であり、実案件には使いません。
未置換の社内設定はscriptが処理開始前に拒否します。

次に開発者のPCで社内GitHubへ認証します。hostは上記と同じ値にします。

```powershell
$internalHost = 'REPLACE_WITH_INTERNAL_GITHUB_HOST'
gh auth login --hostname $internalHost
gh auth status --hostname $internalHost
```

社内SSO／認証方式は組織の手順に従い、対象Framework Releaseの読取権限を持つことを確認します。
`Prepare`は設定ファイルにあるhostをghへ渡して取得します。Git cloneの認証だけではRelease取得の認証を代替しません。
CIの資格情報は開発者の認証とは分けて管理します。

社内用設定を別ファイルに保持して確認したい場合は、次の指定も可能です。

```powershell
pwsh ./tools/run-local.ps1 -Action Prepare -ConfigurationFile ./tools/company-koiki.properties
```

指定ファイルは社内用exampleと同じ項目を持つものを用意します。
既定設定を書き換えた場合は、以後の`Prepare`に追加引数は不要です。

## 4. 準備：DB起動・Framework取込・build

初回は次の1コマンドです。`.env.example`を先にcopyする必要はありません。

```powershell
pwsh ./tools/run-local.ps1 -Action Prepare
```

実行内容は次の順です。

1. `.env`がない場合だけ、DB passwordとHMAC鍵をランダム生成する。
2. `.env`を読み、`docker compose up -d --wait db`でDBのhealthyを待つ。
3. Framework ZIPを取得し、ZIP・同梱installer・内部payloadのhashを照合してMavenへ登録する。
4. `mvnw.cmd verify`でcompile・test・実行可能JAR生成を行う。

既存`.env`は上書きしません。`.env.example`をcopy済みの場合は空欄を先に設定してください。
書式は`KEY=value`で、scriptは値として読みます。shell式やPowerShell codeは記述しません。
`.env`の値を共有log・画面capture・Gitへ含めないでください。

初回は依存downloadに時間がかかります。途中で失敗した場合は§10で原因を確認し、成功するまでCreateAdminへ進みません。
正常時には順に次のような出力が現れ、最後に準備完了が表示されます。

```text
KOIKI 0.1.0-preview.2 ready: 14 coordinates / 42 payloads verified in ...
BUILD SUCCESS
Ready. Next: pwsh ./tools/run-local.ps1 -Action CreateAdmin
```

初版では13 test、Failures／Errors／Skippedはすべて0です。将来test数が増えても、正常終了と失敗件数を確認してください。

### 4.1 生成物を確認する

```powershell
docker compose ps db
Test-Path ./.env
Test-Path ./.koiki/downloads/koiki-framework-0.1.0-preview.2.zip
Test-Path ./target/koiki-java-scaffold.jar
```

DBがrunning／healthy、3つのファイル確認がすべて`True`であることを確認します。
`.env`は存在確認だけにとどめ、内容をlogへ出しません。

ZIPのhashを自分でも確認する場合：

```powershell
(Get-FileHash ./.koiki/downloads/koiki-framework-0.1.0-preview.2.zip -Algorithm SHA256).Hash.ToLowerInvariant()
```

preview.2の期待値：

```text
7858dc3719ed9f8f0fab164e6dfa2e6015893dd971b0d7bd6a48a37136aab08e
```

### 4.2 Maven登録とアプリJARへの同梱を確認する

既定のMaven repositoryを使う場合、PowerShellで次を実行します。

```powershell
$mavenRepo = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository'
$markerPath = Join-Path $mavenRepo '.koiki-distributions/0.1.0-preview.2.json'
$marker = Get-Content $markerPath -Raw | ConvertFrom-Json
$marker | Select-Object schemaVersion, version, coordinates, payloads
Get-ChildItem (Join-Path $mavenRepo 'org/koikifw/koiki-starter-web-mvc/0.1.0-preview.2')
jar tf ./target/koiki-java-scaffold.jar | Select-String 'BOOT-INF/lib/koiki-'
jar tf ./target/koiki-java-scaffold.jar | Select-String 'BOOT-INF/lib/.*-(sources|javadoc)\.jar$'
```

markerはschemaVersion=2、version=0.1.0-preview.2、coordinates=14、payloads=42です。
Web MVC Starterの登録先にはPOM・本体JAR・sources JARがあります。
アプリJAR内のKOIKI依存がpreview.2であること、最後のclassifier検索が無出力であることを確認します。
全14座標分のJARがアプリに入るわけではありません。

## 5. 初回管理ユーザーを登録する

DBが起動し、§4が成功した後に実行します。JavaのWebアプリはまだ起動しなくて構いません。

```powershell
pwsh ./tools/run-local.ps1 -Action CreateAdmin
```

対話に従い、実際にログインに使うメール、password、確認passwordを入力します。
メールはASCII、passwordは15〜128文字かつUTF-8で72 byte以内です。passwordは非表示です。
本書に固定のpasswordはありません。入力値をcommand lineへ直接書かないでください。

正常時：

```text
Initial local administrator created. Role: SCAFFOLD_ADMIN; permission: SCAFFOLD:ADMIN.
```

この操作は同じアプリJARを非Webで起動し、migration適用後、初期ユーザーと管理用role／permission・Auditを登録して終了します。
`SCAFFOLD:ADMIN`はアプリ側の管理用権限の印です。管理画面や全routeの認可を迂回する特権は提供しません。

ユーザーが1件でも存在する場合は拒否します。再実行によるpassword変更・追加ユーザー作成はできません。
登録成功後は繰り返す必要がありません。本番向け初期登録・password回復とは分けたローカル開発用経路です。

## 6. 起動方式を選び、browserで確認する

§4〜5までを終えたら、AかBの片方を選んで起動します。DBは共通です。

### A. JAR実行：PC上のJavaでアプリを起動する

```powershell
pwsh ./tools/run-local.ps1 -Action Start
```

PC上のJavaでアプリJARを実行します。このterminalは起動中のまま保持します。
正常時は`Tomcat started on port 8080`、`Started ScaffoldApplication`等が表示されます。
DB等の確認コマンドは別のPowerShell terminalを同じrepository rootで開いて実行します。
起動済みアプリcontainerがある場合は、先に§7のStopContainerで停止します。

### B. コンテナ実行：Dockerでアプリを起動する

PC上でJARを実行中であれば、そのterminalでCtrl+Cを押して終了を待ちます。
その後、次のコマンドを実行します。

```powershell
pwsh ./tools/run-local.ps1 -Action StartContainer
```

scriptは、生成済みの`target/koiki-java-scaffold.jar`からimageをbuildし、Composeのapp profileでDBとアプリを起動します。
DB healthyと`/login`のHTTP200を確認した後、次のメッセージでterminalへ戻ります。
初回はJava runtime imageのdownloadにも時間がかかります。

```text
Application container ready: http://127.0.0.1:8080/ (use -Action StopContainer to stop the app).
```

```powershell
docker compose --profile app ps
docker compose --profile app logs --tail 100 app
```

appがrunning、dbがhealthyであることを確認します。
DB接続先はcontainer間の`db:5432`となり、appの公開portは`127.0.0.1:8080`だけです。
コンテナは非root userで動作し、Framework ZIP取得やMaven buildをコンテナ内では行いません。
既存Dockerfileとcompose.yamlを共用し、新しい設定ファイルは不要です。

### 6.1 browserでの確認（両方式共通）

[http://127.0.0.1:8080/](http://127.0.0.1:8080/)を開き、次を確認してください。

- 未ログイン時は`/login`へ移動する。
- §5で登録したメール・passwordでホームが表示される。
- User IDが表示され、Permissionsに`SCAFFOLD:ADMIN`が含まれる。
- ログアウトbuttonを押すとログイン画面へ戻る。
- ログアウト後にホームURLを開き直すとログインが必要になる。

Startは起動引数、StartContainerはローカルComposeのcommandで`server.servlet.session.cookie.secure=false`を指定します。
JARに含む通常設定のSecure=true・HttpOnly=true・SameSite=Laxは維持します。本書の起動はローカルHTTP用です。
browserの開発者ツールでもCookieのHttpOnly・SameSite=Laxを確認できます。
Session Cookieの値やpasswordを検証記録へ貼り付けないでください。

## 7. 停止・再開・方式切替・変更後のbuild

### 7.1 アプリの停止

| 起動方式 | 停止方法 | DB |
| --- | --- | --- |
| A: JAR | 起動terminalでCtrl+C。Java終了を待つ | 起動したまま |
| B: コンテナ | 次のStopContainerを実行 | 起動したまま |

```powershell
pwsh ./tools/run-local.ps1 -Action StopContainer
```

StopContainerはappだけを停止します。DB containerとvolumeは削除しません。
JARへCtrl+Cを送るとscriptが中断／終了errorを表示する場合も、Java processが終了したことを確認します。

DBも止めるときだけ、次を実行します。

```powershell
docker compose stop db
docker compose ps -a
```

DB containerが停止してもvolumeのデータは残り、登録したユーザーを再利用できます。
`docker compose down -v`はvolumeを削除するため通常停止には使いません。
`.env`も保持します。既存DBのpasswordはvolume内に残るため、`.env`を再生成しただけではDB側passwordは変わりません。

### 7.2 次回の再開

JAR方式：

```powershell
docker compose up -d --wait db
pwsh ./tools/run-local.ps1 -Action Start
```

コンテナ方式：

```powershell
pwsh ./tools/run-local.ps1 -Action StartContainer
```

StartContainerはDBの起動も行います。どちらも同じユーザーでログインします。CreateAdminの再実行は不要です。

### 7.3 方式を切り替える

- JAR→コンテナ：JARをCtrl+Cで終了させ、StartContainerを実行。
- コンテナ→JAR：StopContainerを実行し、Startを実行。

同じportを使うため同時には起動しません。DBを作り直したりユーザーを登録し直したりする必要はありません。

### 7.4 codeやFramework版を変更した場合

先に現在の方式のアプリを停止し、Prepareでverify・JAR生成を行います。その後、選んだ方式で起動します。

```powershell
pwsh ./tools/run-local.ps1 -Action Prepare
# 片方を選ぶ
pwsh ./tools/run-local.ps1 -Action Start
# または
pwsh ./tools/run-local.ps1 -Action StartContainer
```

Startは再buildしません。StartContainerはimageをbuildしますが、Javaのcompileは行いません。
Prepareを省くと古いJARの内容で起動します。独自Maven repository／配布設定を使う場合は、§8の引数もPrepareへ再指定してください。

Framework版更新では、Parent版、配布版・ZIP hash・installer hash、レビュー済み同梱installerを同じ変更で更新します。
最新版への自動追従はありません。設定不整合やhash不一致を無視せず、レビュー済みの更新を利用してください。

## 8. Maven repositoryを変更する場合とIDE

通常は既定のユーザー単位repositoryを使います。
Mavenの`settings.xml`で独自localRepositoryを設定している場合も、取込scriptはそれを自動で読み取りません。
独自pathを使うPCでは取込・build・IDEを明示的に揃えてください。

```powershell
$localRepo = 'C:\work\maven-repository'
pwsh ./tools/run-local.ps1 -Action Prepare -LocalRepository $localRepo
```

この指定でFramework取込とアプリbuildの両方が同じrepositoryを使います。
§4.2の確認を行う場合は、`$mavenRepo`にも同じpathを設定します。
IDEにも同じMaven local repositoryとJDK 21を設定し、Maven projectを再読込します。
source表示／Javadoc参照はIDEで別途確認します。

個別の取込・buildを行う場合のWindowsコマンドは次の組合せです。

```powershell
pwsh ./tools/prepare-koiki-distribution.ps1 -LocalRepository $localRepo
./mvnw.cmd "-Dmaven.repo.local=$localRepo" verify
```

CreateAdmin／Start／StartContainerは生成済みの実行可能JARを使うため、Maven repositoryの指定は不要です。

## 9. 作成・更新されるファイル

| 場所 | 用途・扱い |
| --- | --- |
| `.env` | PC固有のDB password・HMAC鍵。Git対象外。外部へ共有しない |
| `.koiki/downloads/` | 配布ZIPのcache。Git対象外。ZIPは次回もhash照合される |
| ユーザーの`.m2/repository/`等 | Framework・第三者依存・plugin。build／IDEで参照する |
| repository内の`target/` | アプリJAR・test結果。Git対象外。再buildで更新する |
| Composeの`db-data` volume | DB永続データ。ユーザー・Session・Audit等を保持する |

clone先には本sessionの検証用ユーザー・`.env`・password・DBデータは含まれません。
各PCでPrepareとCreateAdminを実行して用意します。

## 10. 失敗した場合の確認先

エラーを無視して次の段階へ進まず、原因を直して同じ段階からやり直します。

| 症状 | 確認と対処 |
| --- | --- |
| host／repository設定が拒否される | §3.1のREPLACE値を実在する社内配布先へ置き換える。hostはschemeなし、repositoryはowner/repo |
| run-local.ps1がない／preview.1になっている | §3のbranch／tag・commitを確認。本手順を含む版が公開されている必要がある |
| pwsh／javac／javaが見つからない、Java版エラー | PowerShell 7・JDK 21のinstall、PATH・JAVA_HOMEを確認しterminalを開き直す |
| Docker Serverへ接続できない | Dockerを起動し、`docker version`のServer応答・Linux container設定を確認 |
| 5432／8080が使用中 | §2のport確認で既存processを特定する。別用途のDB／Javaを無断停止せず、環境を整理して再実行 |
| .envの値が空、Unsupported .env syntax/key | `.env.example`を参照しKEY=valueで設定。Prepareは既存.envを補完・上書きしない |
| DBがhealthyにならない／DB認証失敗 | `docker compose ps -a db`と`docker compose logs --tail 100 db`を確認。既存volumeと.envのpassword不一致にも注意。volumeを安易に削除しない |
| ZIP取得失敗、HTTP／DNS／TLSエラー | 社内host・repository・gh認証／読取権限・proxy・社内証明書設定を確認。publicへ切り替えて回避しない |
| ZIP／installerのhash不一致 | 先へ進まない。固定設定と公開版、作業treeの改変を確認。scriptは不一致cacheを再取得する。期待hashをdownload結果に合わせて書き換えない |
| Conflicting installed coordinate | 同じ版の登録済みbytesが異なる。既存repositoryを一括削除せず、由来を確認。調査用の新しい専用repositoryで§8を実行し切り分ける |
| .koiki-prepare.lockが残っている | 別の取込processが動作していないか確認。動作中は削除しない。中断したprocessが終了済みと確認できた場合だけ、対象repositoryのlockを除去して同じZIPで再実行 |
| Non-resolvable parent POM | Framework取込の成功と版を確認。取込・Maven・IDEのrepository pathが一致しているか確認 |
| 第三者依存／Maven pluginが解決できない | Maven Centralの通信・proxy・証明書・Maven設定を確認。Framework ZIPには第三者JARは入っていない |
| testが失敗する | `target/surefire-reports/`と失敗前のlogを確認。testをskipして成功扱いにせず、Docker／image取得／接続等を切り分ける |
| Initial registration refused／already has users | 登録済みなので同じユーザーでStartから進む。CreateAdminはpassword resetではない |
| passwordが拒否される／確認不一致 | §5の文字数・UTF-8 byte数・確認入力を確認。入力したpasswordをlogへ出さない |
| ログイン後も戻される | DB状態、登録時のメール・password、StartによるHTTP用Cookie設定、browser Cookieを確認 |
| container image buildが失敗する | Dockerfileのbase image取得経路・proxy・registry制限とbuild logを確認。imageを任意の版へ置き換えて成功扱いにしない |
| コンテナ起動後にreadinessがtimeout | `docker compose --profile app ps`と`docker compose --profile app logs --tail 100 app`を確認。停止やvolume削除は自動では行わない |
| JARがない／変更が画面へ反映されない | PrepareのBUILD SUCCESSとtargetのJAR生成を確認し、Javaを再起動する |

既知の想定内WARN：アプリmigrationが未作成のための`No migrations found`、
独自AuthenticationProvider構成による`UserDetailsService`未使用の警告。
これらだけで失敗と判定せず、全体のBUILD SUCCESS／アプリ起動結果を確認します。

## 11. 初回検証の記録

再現確認時は、次を記録すると手順書の訂正と次のPCへの展開に使えます。

| 項目 | 記録内容 |
| --- | --- |
| 対象source | 公開branch／tag名と`git rev-parse HEAD` |
| 環境 | OS、JDK、PowerShell、Docker／Composeの版 |
| Maven登録先 | 既定／独自pathの区分（共有時は個人名部分を伏せる） |
| Prepare | 成否、14座標／42 payload、BUILD SUCCESS、test結果 |
| CreateAdmin | 成否、再実行不要であることの確認。メール・passwordの値は記録しない |
| 起動方式 | JAR／コンテナ。両方を確認した場合はそれぞれ記録 |
| browser操作 | login、SCAFFOLD:ADMIN表示、logout、ホーム再アクセスの結果 |
| 停止・再開 | ユーザーを保持して再ログインできるか |
| 問題 | 失敗段階、secretを除去したerror、対処、再確認結果 |

関連文書：[README](../../README.md)、[KOIKI Framework採用方針・検証記録](../policy/koiki-framework-adoption-policy.md)。
