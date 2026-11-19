# Rancher DesktopでのKubernetesローカル実行

版：v0.2（2026-11-19、Windowsローカル検証結果を反映）

対象：Rancher Desktop、Moby／dockerd、Kubernetes **1.37.2**、PowerShell 7、JDK 21。
WindowsのVSCode terminalでPrepare、初回管理ユーザー登録、Pod起動、実browserでのlogin・権限表示、
停止・再開を確認済みです。修正版CreateAdminは成功・登録済み拒否の両方を確認しました。詳細は§9を参照してください。

## 1. Composeとの使い分け

本書はKubernetes専用です。JAR／Compose実行は[既存の構築手順書](local-setup-guide.md)を使います。
Kubernetesを使う場合に`run-local.ps1`を実行する必要はありません。
特に、KubernetesのCreateAdminを既存scriptのCreateAdminへ置き換えないでください。接続するDBが異なります。

| 対象 | JAR／Compose | Kubernetesローカル |
| --- | --- | --- |
| 操作script | `tools/run-local.ps1` | `tools/run-kubernetes-local.ps1` |
| 配備定義 | `compose.yaml` | `deploy/kubernetes/overlays/local/` |
| アプリimage | `koiki-java-scaffold:local` | `koiki-java-scaffold:k8s-local` |
| DB | Composeの`db`／既存volume | 専用namespace内の`scaffold-db`／専用PVC |
| 秘密値 | repository rootの`.env` | `.koiki/kubernetes-local/secrets.json` |
| ブラウザURL | `http://127.0.0.1:8080/` | `http://127.0.0.1:18080/`（Forward中） |
| Session Cookie | 既存設定 | 既存設定（方式切替時にbrowser側を消去） |

Dockerfile、Framework配布設定、ユーザーのMaven repository、`target/`のアプリJARは共用します。
DBデータ・秘密値・DB内のSessionは分離します。Compose側の管理ユーザーはKubernetes側に引き継がれません。
**JAR／ComposeとKubernetesの同時実行は禁止です。DBも含め、片方を停止してから切り替えます。**
scriptはKubernetesの準備・登録・起動・forward前に、このCompose projectの稼働containerとPCの8080使用を確認し、検出時は拒否します。
方式切替中の同時操作や、別名のCompose project・別portのJava processまでは自動検出しません。利用者も実行方式を確認します。
既存の`run-local.ps1`にはKubernetesの稼働を検出する逆方向のcheckはありません。
相互排他が完全自動であるとは扱わず、Composeへ戻す際も§7の停止確認を行ってください。
Cookieはportでは分離されないため、方式を切り替えるときは`127.0.0.1`のCookieをbrowserで消去し、古い画面を閉じてから新しいURLを開きます。
Kubernetes専用のCookie名は追加しません。

## 2. 配置と確認範囲

```text
deploy/kubernetes/
  base/                    アプリDeployment・ClusterIP Serviceの共通定義
  overlays/local/          ローカル用namespace・PostgreSQL・HTTP設定
tools/run-kubernetes-local.ps1
```

専用namespaceは`koiki-scaffold-local`、操作contextは`rancher-desktop`に固定します。
scriptはAPI接続先がloopbackであることと、server versionが1.37.2（K3s suffixを許容）であることを確認します。
現在選択中のcontextや既定namespaceは変更しません。クラスタ設定全体のresetも行いません。

baseは将来の配備設定で再利用する出発点です。単独適用はせず、local overlayを使います。
EKSの配備設定は含みません。IAM、ECR、ALB／TLS、VPC、RDS、secret管理、可用性・監視はAWS配置時に設計します。
ローカル用のPostgreSQL Pod・`local-path`ストレージ・HTTP用Cookie設定をそのまま本番へ持ち込みません。

## 3. 前提確認

Rancher DesktopでKubernetes 1.37.2を起動し、container engineは既存と同じMoby／dockerdを使います。
containerdへ切り替える必要はありません。本scriptはDocker CLIでimageを作るため、containerd／nerdctl方式には対応しません。

VSCodeのPowerShell 7 terminalをrepository rootで開きます。

```powershell
git status --short --branch
java -version
javac -version
docker version
docker context show
kubectl --context rancher-desktop version
kubectl --context rancher-desktop get nodes
kubectl --context rancher-desktop get storageclass
```

JDK21、Docker Server応答、Kubernetes server 1.37.2、nodeのReady、`local-path` StorageClassを確認します。
Docker CLIの接続先は、Kubernetesと同じRancher DesktopのMoby Engineにしてください。
kubectlもserverと互換性のある版を使います（今回の基準は1.37系）。
失敗した場合は設定を確認してから先へ進みます。別のクラスタへcontextを置き換えて実行しません。

既存JARが動いている場合は、そのterminalでCtrl+Cを押して終了を待ちます。
Composeを利用していた場合は、DBも含めて停止します（volumeは保持）。

```powershell
docker compose --profile app stop
```

`.env`未作成でCompose設定の展開が失敗する場合は、Composeをまだ準備していないことと、
`docker ps`にこのprojectの稼働containerがないことを確認してください。Kubernetesのために`.env`を生成する必要はありません。
停止後、browserの`127.0.0.1`のCookieを消去します。

Frameworkの取得設定は既存の`tools/koiki-distribution.properties`を共用します。
公開Scaffoldの検証ではpublic設定を使用できます。実案件は[既存手順書§3.1](local-setup-guide.md#31-社内framework-releaseの取得先を設定する初回準備の前に必須)の社内配布設定を先に済ませます。

## 4. Prepare：buildとKubernetes専用DBの準備

```powershell
pwsh ./tools/run-kubernetes-local.ps1 -Action Prepare
```

実行する処理は次のとおりです。

1. ローカルクラスタと、Kubernetesアプリが停止中であることを確認する。
2. 既存の取得wrapperで固定版Frameworkを照合・Maven登録する。
3. Maven Wrapperで`verify`を実行し、アプリJARを生成する。test用PostgreSQLは従来どおりTestcontainersで実行する。
4. 共通Dockerfileから`koiki-java-scaffold:k8s-local`をbuildする。
5. 専用namespaceを作り、初回だけ独立したランダム秘密値を生成してSecretへ登録する。
6. local overlayを適用し、PostgreSQLのReadyを待つ。アプリはreplicas=0で保持する。

正常時は`Kubernetes Prepare complete.`を表示します。
確認には次を使います。

```powershell
pwsh ./tools/run-kubernetes-local.ps1 -Action Status
```

この段階ではDB PodがReady、PVCがBound、アプリDeploymentは0 replicaが正常です。
テーブル作成は次のCreateAdminによるJava起動時にFlywayが行います。
既存のCompose DBには接続しません。

独自のMaven repository／Framework配布設定を使う場合：

```powershell
pwsh ./tools/run-kubernetes-local.ps1 -Action Prepare `
  -LocalRepository 'C:\work\maven-repository' `
  -ConfigurationFile './tools/company-koiki.properties'
```

必要な引数だけ指定します。`company-koiki.properties`は自分の環境用に用意した設定ファイルの例です。
秘密値ファイルはGit対象外です。値を画面・logへ出さず、`.env`からcopyしません。
既存Secretとローカルファイルが食い違う場合や、既存Secret／PVCがあるのにファイルが失われている場合は停止します。
DBを保持したまま秘密値を再生成しても、DB passwordは自動で変更されません。

## 5. CreateAdmin：Kubernetes DBへの初回登録

```powershell
pwsh ./tools/run-kubernetes-local.ps1 -Action CreateAdmin
```

Kubernetes用に登録するメール、password、確認passwordを対話入力します。
条件は既存と同じで、メールはASCII、passwordは15〜128文字かつUTF-8で72 byte以内です。

scriptは自分で起動したDB port-forward（`127.0.0.1:15432`）の開始を確認し、
PC上のJDK21で同じJARを非Web・`local-bootstrap` profileで実行します。
DB接続先はこのforwardへ明示し、passwordは子Javaの標準入力へ渡します。
PCの環境変数とComposeの`.env`を書き換えず、終了時は自分で起動したforwardを終了します。
15432が使用中なら既存processを停止せずに拒否します。

Javaの起動・終了logを表示し、正常時は次の2つのメッセージを確認できます。

```text
Initial local administrator created. Role: SCAFFOLD_ADMIN; permission: SCAFFOLD:ADMIN.
Kubernetes administrator registration confirmed. Next: -Action Start.
```

後者はJavaの正常終了と登録成功メッセージをscriptが確認したことを示します。
入力後はJavaの終了まで待ち、出力がないことだけで登録成功と判断しません。
登録済みDBで再実行した場合は`Identity already has users; initial registration refused.`と
scriptの`Registration failed.`が表示され、非zeroで終了します。この拒否は既存ユーザー保護の想定動作です。

これはローカルPCからの初回登録です。Pod内のJobを実装したものではありません。
Javaのloopback限定・空Identity限定の制約は維持します。
ユーザーが存在する場合は拒否し、passwordの変更・再登録はしません。
この登録手順をAWSの本番bootstrapとして流用しません。

## 6. Startとブラウザ確認

```powershell
pwsh ./tools/run-kubernetes-local.ps1 -Action Start
```

DBのReadyを確認し、アプリを1 replicaへ増やします。アプリimageは外部registryからpullせず、
Rancher Desktop内のbuild済みimageを使います（local overlayの`imagePullPolicy: Never`）。
起動待ちとreadinessは`/login`のHTTP応答で確認します。
これはloginページの応答確認であり、DB正常性や認証成功の証明とは分けます。
専用のliveness endpoint・probeは今回追加していません。

`Kubernetes app ready.`の後に実行します。

```powershell
pwsh ./tools/run-kubernetes-local.ps1 -Action Forward
```

このterminalを保持し、[http://127.0.0.1:18080/](http://127.0.0.1:18080/)を開きます。

- 未ログイン時にlogin画面へ移動する。
- §5のユーザーでloginし、`SCAFFOLD:ADMIN`が表示される。
- logoutでき、ホームへ再アクセスするとloginが必要になる。

Pod内の待受は8080です。PC側へはloopbackの18080だけをforwardし、NodePort・Ingress・LoadBalancerは使いません。
ローカルHTTP用にだけSecure Cookieをfalseへ上書きし、JARに含む既定値は維持します。
Cookie・password・Secretの値は検証結果へ貼り付けません。

### 6.1 KubernetesのPodで動いていることを確認する

Forwardを保持したまま、別のterminalで実行します。

```powershell
kubectl --context rancher-desktop --namespace koiki-scaffold-local get pods -l app.kubernetes.io/name=scaffold -o wide
kubectl --context rancher-desktop --namespace koiki-scaffold-local logs deployment/scaffold --prefix --tail=80
```

PodのREADYが`1/1`、STATUSが`Running`であることと、NODEを確認します。
logの`[pod/<Pod名>/app]`が取得元です。`Tomcat started on port 8080`、`Started ScaffoldApplication`、
接続先`jdbc:postgresql://scaffold-db:5432/scaffold`を確認します。
Springの起動メッセージ自体はComposeでも同じなので、対象context／namespaceのPod情報とlogを合わせて判断します。
アプリはPod内の8080で待ち受け、PCの18080はport-forwardの入口です。

## 7. 停止・再開・変更後のbuild

まずForwardのterminalでCtrl+Cを押します。これは通信のforwardだけを終了し、Podは停止しません。
別のterminalで、次を実行します。

```powershell
pwsh ./tools/run-kubernetes-local.ps1 -Action Stop
pwsh ./tools/run-kubernetes-local.ps1 -Action Status
```

Stopはアプリ→DBの順に0 replicaへ減らし、PVC・SecretとDBデータを保持します。
再開は次の順です。

```powershell
pwsh ./tools/run-kubernetes-local.ps1 -Action Start
pwsh ./tools/run-kubernetes-local.ps1 -Action Forward
```

同じユーザーでloginできることを確認します。CreateAdminの再実行は不要です。
Podの入替後は古いport-forwardが終了するため、Forwardを再実行します。

code変更後はStop→Prepare→Start→Forwardです。独自の取得設定・Maven repository引数もPrepareへ再指定します。
StartはJavaのcompileもimage buildも行いません。Prepare後にJAR・Dockerfile・imageが変わった場合はStartを拒否します。
ComposeとJARを共用しているため、Compose側で再buildした場合もKubernetesのPrepareをやり直します。

次のStartでDBも1 replicaへ戻します。PVCを削除しなければ既存データを再利用します。
JAR／Compose方式に戻すときはStopとStatusで両方のPodの停止を確認し、
Forwardを終了、browserのCookieを消去してから既存の起動手順へ戻ります。
namespace／PVCの削除やRancher Desktopのresetは通常停止ではありません。DBデータを失う可能性があるため、本scriptには削除操作を設けていません。

### 7.1 検証用DBを空にして初回登録をやり直す場合だけ

**この操作はKubernetes専用DBのユーザー・Session・Auditを含む全データを破棄します。通常の再開には不要です。**
必要なデータを退避し、検証用DBを作り直すと判断した場合だけ実行します。
Composeのvolume、KubernetesのSecret、`.koiki/kubernetes-local/secrets.json`は削除しません。

ForwardをCtrl+Cで終了し、次を実行します。

```powershell
pwsh ./tools/run-kubernetes-local.ps1 -Action Stop
pwsh ./tools/run-kubernetes-local.ps1 -Action Status
```

Deployment／StatefulSetが両方`0/0`で、Podが表示されないことを確認します。
この時点のPVCのVOLUME値を控え、次の**専用PVCだけ**を削除します。

```powershell
kubectl --context rancher-desktop --namespace koiki-scaffold-local delete pvc data-scaffold-db-0
```

削除完了メッセージが表示されるまで待ちます。Prepareだけを繰り返しても既存DBは空になりません。
削除が完了した後に実行します。

```powershell
pwsh ./tools/run-kubernetes-local.ps1 -Action Prepare
pwsh ./tools/run-kubernetes-local.ps1 -Action Status
```

PVCが`Bound`でVOLUMEが以前と異なることを確認します。同じ値なら再作成済みとは判断せず、削除結果を確認します。
本手順はRancher Desktopの`local-path`ストレージを対象とします。別のstorageではPVの再利用・保持方針も確認が必要です。
新しいDBで、初回登録からやり直します。

```powershell
pwsh ./tools/run-kubernetes-local.ps1 -Action CreateAdmin
pwsh ./tools/run-kubernetes-local.ps1 -Action Start
pwsh ./tools/run-kubernetes-local.ps1 -Action Forward
```

CreateAdminの成功メッセージ2つと、新しく登録したユーザーのlogin・`SCAFFOLD:ADMIN`表示を確認します。
空のDBではCreateAdminのJava起動時にFramework migrationが適用されます。

## 8. 問題の確認

```powershell
pwsh ./tools/run-kubernetes-local.ps1 -Action Status
kubectl --context rancher-desktop --namespace koiki-scaffold-local get events --sort-by=.lastTimestamp
kubectl --context rancher-desktop --namespace koiki-scaffold-local logs deployment/scaffold --tail=100
kubectl --context rancher-desktop --namespace koiki-scaffold-local logs statefulset/scaffold-db --tail=100
```

| 症状 | 対応 |
| --- | --- |
| context／version／node確認で停止 | Rancher DesktopのKubernetesとkubectl設定を確認。scriptのcontext制限を外して回避しない |
| 同時実行禁止で停止 | JARとCompose app／DBの停止を確認。scriptは既存processを自動停止しない |
| `ErrImageNeverPull` | Docker CLIが同じRancher DesktopのMobyを向いているか確認。アプリをStopしPrepareを再実行。containerdへ変更した場合はこの手順を中断 |
| PVCがPending | `local-path` StorageClass、Rancher Desktopの空き容量、eventsを確認。既存PVCを削除しない |
| DB認証エラー／Secret不一致 | ローカル秘密値ファイルと既存DBの由来を確認。ファイルの再生成・Secret上書き・PVC削除で回避しない |
| 15432／18080が使用中 | 既存のforwardなどの用途を確認して、その所有terminalで終了する。Composeは5432／8080を使う |
| CreateAdminが拒否 | 既存ユーザーがある場合はそのユーザーでlogin。password reset手段ではない |
| CreateAdminの入力後に結果が見えない | 再実行前にDB状態を確認する。子Javaの標準出力・標準エラーを明示取得する修正版scriptを使う。登録済みの場合は再登録せずStartから進む |
| 起動timeout／PodがReadyにならない | app／DBのlogとeventsを確認。probeだけ緩めて成功扱いにしない |
| 変更が反映されない | Stop→Prepare→Start→Forwardを実施。Startだけでは再buildしない |
| `OOMKilled`／resource不足 | eventsとPod状態で原因を確認し、Rancher Desktopの割当とmanifestのresource設定を見直す |

想定内WARNは既存手順書と同じです（アプリmigration未作成、独自AuthenticationProvider構成）。
logを共有する際は秘密値・個人情報を除去します。SecretのYAML／JSONは貼り付けません。

## 9. 検証記録

クラスタへ接続しない確認は次のコマンドで再実行できます。

```powershell
pwsh ./tools/tests/kubernetes-local-guards.ps1
kubectl kustomize ./deploy/kubernetes/overlays/local
git diff --check
```

guard確認はkubectl／Dockerを代替するfixtureで、接続先・version・同時実行・Secret不一致時の拒否を対象にします。
実際のKubernetes APIへの適用、port-forwardやJavaによる登録成功を証明するものではありません。

| 項目 | 状況 |
| --- | --- |
| 静的・オフライン確認 | PowerShell構文解析、guard確認11件、Kustomize展開、git diff --check成功 |
| 実施日・対象 | 2026-11-19、`feat/local-kubernetes`のRancher Desktop用設定。Framework 0.1.0-preview.2 |
| 環境 | Windows／VSCode、Temurin JDK21.0.13、PowerShell7.6.10、Rancher Desktop Moby／Docker Engine29.5.3、Kubernetes1.37.2、local-path |
| Kubernetes起動 | 利用者が1.37.2で起動。scriptのversion制限を通過し、node Readyを確認 |
| Framework取込 | 利用者提示logで14座標／42 payloadの照合完了を確認。public設定、既定のユーザーMaven repositoryを使用 |
| Maven verify／Docker build | 利用者提示logでBUILD SUCCESS、test13件・failure／error／skip0、`koiki-java-scaffold:k8s-local`のbuild成功を確認 |
| namespace・Secret・DB・PVC | 利用者提示logで作成・DB起動待ち完了。PVCはBound |
| 初回登録・初回の表示問題 | 初版ではJava logが見えなかったが、DB Podでuser_count=1、その後のlogin・権限表示で登録成功を確認。子Javaの標準出力・標準エラーを明示取得するよう修正 |
| 修正版の再登録拒否 | 利用者提示logで既存ユーザー検出、Java例外とscript失敗表示を確認。成功扱いにしない |
| app起動・DB接続 | 対象Podのprefix付きlogで`/app/app.jar`、port8080、`scaffold-db:5432/scaffold`接続、アプリ起動完了を確認 |
| 実browser操作 | 利用者が18080経由でloginと`SCAFFOLD:ADMIN`表示を確認 |
| Stop→Start | 利用者提示Statusでapp／DBとも0/0、Podなし、PVC Boundを確認。再実行後の動作も利用者が確認 |
| DB再作成 | 利用者が専用PVC削除を実行し、再Prepare後にVOLUMEが別の値へ変わったことを確認。Secret・秘密値ファイルは保持 |
| 修正版の登録成功 | 新DBの利用者提示logでFramework migration3件適用、Java正常終了、成功メッセージ2つを確認。続けて利用者がlogin・権限表示を確認 |
| EKS／AWS実接続 | 対象外 |

利用者がVSCode terminalで実行したlogと実browser操作の報告、およびクラスタを変更しない静的確認に基づく記録です。
Kubernetesでのlogout・logout後のホーム再アクセス、Cookie属性、DB Session／Audit件数、
containerの実行user・終了code・OOM、異常系guardの実クラスタ上での動作は個別確認済みとは扱いません。
初回表示問題の原因をWindows全般の不具合とは断定せず、修正版で成功・拒否両方のlog表示を確認した結果を記録します。
既存のWindows Compose／JAR検証結果をKubernetesの実行証拠として扱いません。
今後の再現検証では環境版と各段階の結果を追記します。メール・password・Cookie値・Secret内容は記録しません。

参照：[Rancher Desktopのimage操作](https://docs.rancherdesktop.io/tutorials/working-with-images/)、
[Kustomize](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/)、
[port-forward](https://kubernetes.io/docs/tasks/access-application-cluster/port-forward-access-application-cluster/)。
