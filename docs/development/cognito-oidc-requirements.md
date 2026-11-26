# Amazon Cognito による OIDC 認証の要件案

状態：レビュー用の初稿。対象は最小の Spring MVC アプリを AWS に配置する際のブラウザー認証。AWS リソースの作成、実装、本番への切替は後続作業とする。

実装内容、Cloud での検証結果、VS Code での再開手順は[引継ぎ文書](cognito-oidc-handoff.md)を参照。

## 目的と範囲

- AWS 環境では Amazon Cognito User Pool の Managed Login で本人確認を行い、ログイン後に既存のホーム画面でアプリのユーザー ID と権限を表示できるようにする。
- AWS ではローカルのメール・パスワード認証と初回管理者登録（`CreateAdmin`）を無効にする。Cognito の障害や設定不備があってもローカル認証に切り替えない。従来のローカル認証は、Cognito を使わない開発・テスト用として明示的に選べる構成を初期案とする。
- PostgreSQL と JDBC セッションは維持する。REST API の Bearer 認証、Identity Pool、ブラウザーからの AWS API 呼び出し、ユーザー管理画面は初回の対象外とする。

## ログインとセッション

1. 未認証でホームにアクセスした利用者を Cognito のログインへ誘導する。Spring Security の OAuth2 Client を用い、サーバー側の機密クライアントで Authorization Code Flow と PKCE（S256）を使う。初期スコープは `openid` のみとし、表示等で必要になった場合に限り追加する。
2. アプリのコールバックで認可レスポンスの `state` と ID トークンの `nonce` を照合し、署名・発行者・対象クライアント・有効期限を検証する。Cognito のログイン用ドメインと、トークン検証に使う User Pool の issuer は別の値として扱う。検証に失敗した場合はセッションを成立させず、安全なエラー表示と再試行導線を提供する。
3. 検証済みの `(iss, sub)` でアプリユーザーを特定する。メールアドレスは本人の対応付けや権限の自動付与に使わない。許可された利用者にのみアプリセッションを発行し、ログイン時にセッション ID を更新する。
4. ブラウザーにはアプリのセッション Cookie のみを渡し、AWS では `Secure`、`HttpOnly`、`SameSite=Lax` を基本とする。Cognito のトークンを Cookie、ブラウザーストレージ、アプリログへ書かない。下流 API を呼ばない初回では、ログイン後にトークンや refresh token を保持する必要がないかを確認し、保存を最小化する。
5. ログアウトは CSRF 保護された POST で受け付け、JDBC セッションを無効化してセッション Cookie を破棄する。その後 Cognito の `/logout` に遷移し、Cognito 側のブラウザーセッションも終了する。戻り先は登録済みの固定 URL とする。Cognito 側に接続できない場合も、アプリ側のログアウトは成立させる。将来外部 IdP と連携する場合は、その IdP のセッション終了を別途検討する。

## アプリ利用許可と権限

初期案は、**Cognito が本人確認を担当し、アプリが利用許可と権限を管理する**方式。利用者は事前に登録・対応付けし、Cognito にユーザーが存在するだけでは利用を許可しない。未登録、無効、対応付けが曖昧な利用者は拒否する。初期管理者の対応付けと `SCAFFOLD:ADMIN` の付与は明示的に行う。Cognito グループを権限として採用する場合は、グループ管理者とアプリの権限管理者の責任分担を決めたうえで、明示的な割り当て規則を定義する。

現行ホーム画面は `FrameworkPrincipal` のユーザー ID・権限を参照している。KOIKI 0.1.0-preview.2 の公開 API には `IdentityAdministration.linkExternalIdentity(...)` と `AuthenticationSource.OIDC` がある。一方、`IdentityQuery` の検索は `findById` のみ。公開 API で可能な外部 ID の解決、無効状態と権限の取得、既存セッションの失効、最初の管理者の登録経路を実装前に確認する。現行の `CreateAdmin` はローカル専用のため、AWS 用の登録手順として流用しない。

公開 API だけで要件を満たせる場合は、既存のユーザー ID・権限のモデルを維持する。足りない場合は、アプリ所有のストアで `(issuer, subject)` と利用許可・権限を管理する案と、必要な公開機能が提供された Framework 版を採用する案を比較して決める。Framework の内部クラスやテーブルへの直接アクセスを前提にしない。既存ローカルアカウントを引き継ぐ場合は、運用者による明示的な対応付けと検証を別途定義する。

## AWS 環境と運用

- 環境ごとに User Pool、issuer、Managed Login ドメイン、App Client、公開 HTTPS URL、コールバック URL、サインアウト URL を定義する。コールバックの例は `/login/oauth2/code/cognito`。URL は Cognito に完全一致で登録し、外部 URL をリクエストヘッダーから無条件に組み立てない。ロードバランサー等で TLS を終端する場合は、信頼済みのプロキシ経由の情報だけを利用する。
- クライアントシークレットは AWS Secrets Manager 等から実行時に供給する。アプリから Cognito のメタデータ・公開鍵・トークンエンドポイントへの HTTPS 通信を確保する。起動時に必須設定の不足を検出し、誤った設定で認証を許可しない。
- ログイン・拒否・ログアウト・利用許可の変更を追跡できるようにする。認可コード、トークン、シークレットをアプリやプロキシのログに残さない。認可開始・コールバック・ログアウト・静的配信のルートをアプリ側の SecurityFilterChain に明示し、その他のルートは既存の KOIKI フォールバックで拒否する。CSRF と標準セキュリティヘッダーを維持する。

## 受入条件

| 場面 | 確認する結果 |
| --- | --- |
| 正常ログイン | AWS 検証環境で Managed Login を経由し、登録済みユーザーのホームに正しい ID と権限が表示される。管理権限は割り当て済みのユーザーだけに付く。 |
| 利用拒否 | Cognito で認証済みでも未登録・無効ならホームに入れない。別の pool や client、期限切れ、state／nonce 不一致の応答でアプリセッションが作られない。 |
| セッションとログアウト | ログインで Cookie が更新され、HTTPS で適切な属性が付く。ログアウト後は旧セッションでホームに入れず、Cognito 側のセッションも終了する。 |
| 障害・設定不備 | Cognito への接続失敗や設定不備で利用を許可せず、AWS でローカル認証・初回登録が使用できない。 |
| 回帰確認 | ローカル開発用のテストに加え、OIDC の正常系と拒否系を自動テストで確認する。AWS 検証環境で実際の外部 URL と Cognito を使ったログイン・ログアウトを確認する。 |

## 実装前に合意する項目

1. **ユーザー登録**：初期案は運用者による事前登録。既存 User Pool を使うか、環境ごとに新設するか。利用者の招待、初回管理者の付与、MFA の要否を誰が決めるか。
2. **権限の管理先**：初期案はアプリ管理。上記の KOIKI 公開 API の確認後に、ユーザー・権限モデルと登録手順を決める。無効化・権限剥奪を既存セッションに反映する時間と手段も決める。
3. **既存ユーザーと開発環境**：ローカル開発用アカウントの移行が必要か。初期案どおりローカル認証を開発専用で残すか、開発も Cognito に統一するか。
4. **AWS 接続条件**：検証環境のリージョン、公開 HTTPS ホスト名、シークレット管理者、セッション有効期間とログアウト後の戻り先を決める。

参考：[Cognito User Pools の認可エンドポイント](https://docs.aws.amazon.com/cognito/latest/developerguide/authorization-endpoint.html) · [Cognito のログアウトエンドポイント](https://docs.aws.amazon.com/cognito/latest/developerguide/logout-endpoint.html) · [Spring Security OAuth 2.0 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/index.html) · [KOIKI Framework 採用方針](../policy/koiki-framework-adoption-policy.md)

## 開発環境でのコンテナ検証手順

現在の実装では、Cognito の `(iss, sub)` と既存の `FrameworkUserId` の対応だけをアプリのテーブルに保存する。ユーザー状態と権限はログイン時に `IdentityQuery` から取得する。認証セッションは KOIKI ユーザー ID で索引付けされる。対応付け解除や Cognito 側だけのアカウント停止が既存セッションに自動で通知される仕組みはない。即時停止が必要な場合は、KOIKI 側でも利用を停止し、既存セッションを失効させる運用を用意する。

1. リポジトリのルートで、既存の初回準備手順に従ってローカル DB と JAR を用意する。空の検証 DB では `pwsh ./tools/run-local.ps1 -Action CreateAdmin` で検証用の KOIKI ユーザーを作り、表示される内部ユーザー ID を控える。既存の DB では対応付けたいアクティブなユーザーの内部 ID を使う。ローカル認証の初回登録は AWS 用のユーザー作成手順には使用しない。
2. シェルに非秘密の設定を渡す。値は AWS コンソールで確認したものに置き換える。

   ```bash
   export SCAFFOLD_COGNITO_ISSUER='https://cognito-idp.<region>.amazonaws.com/<user-pool-id>'
   export SCAFFOLD_COGNITO_DOMAIN='https://<managed-login-domain>'
   export SCAFFOLD_COGNITO_CLIENT_ID='<app-client-id>'
   export SCAFFOLD_COGNITO_LINK_SUBJECT='<user-sub>'
   export SCAFFOLD_COGNITO_LINK_USER_ID='<framework-user-id>'
   ```

3. イメージをビルドし、ワンショットコマンドで対象ユーザーを対応付ける。この操作には DB にアクセスできる運用者の実行権限が必要。同じ組合せは再実行でき、既存の対応を別のユーザーで上書きすることはできない。

   ```bash
   docker compose --profile app build app
   docker compose --profile app run --rm \
     -e SCAFFOLD_COGNITO_ISSUER -e SCAFFOLD_COGNITO_LINK_SUBJECT -e SCAFFOLD_COGNITO_LINK_USER_ID \
     app --spring.main.web-application-type=none --spring.profiles.active=cognito-link
   ```

4. App Client Secret を安全な保管先から取得し、シェルの履歴に残らない方法で実行環境の `SCAFFOLD_COGNITO_CLIENT_SECRET` に渡す。手元の Bash で対話入力する場合は次のように起動する。Cognito 検証用の Compose 上書きは `127.0.0.1:18080` にだけ公開するため、他のマシンのブラウザーでは SSH ポート転送などでそのポートへ接続する。通信が HTTP のため Secure Cookie を無効にするのはこのローカル用 Compose に限る。

   ```bash
   read -rsp 'Cognito App Client Secret: ' SCAFFOLD_COGNITO_CLIENT_SECRET
   printf '\n'
   export SCAFFOLD_COGNITO_CLIENT_SECRET
   docker compose -f compose.yaml -f compose.cognito.yaml --profile app up -d --wait app
   unset SCAFFOLD_COGNITO_CLIENT_SECRET
   ```

5. ブラウザーで `http://localhost:18080/` を開く。Cognito の登録済みコールバックは `http://localhost:18080/login/oauth2/code/cognito`、サインアウト URL は `http://localhost:18080/signed-out`。ログイン後に期待した内部 ID と権限が表示され、ログアウト後にサインアウト画面が表示されることを確認する。

AWS では `SPRING_PROFILES_ACTIVE=aws` と `SCAFFOLD_COGNITO_PUBLIC_ORIGIN=https://<公開ホスト>` を指定し、その HTTPS オリジンのコールバックとサインアウト URL を登録する。AWS の DB を新規作成する場合は、本番の初回 KOIKI 管理者を用意する運用が別途必要。現行の公開 Identity 管理 API は認証済みの管理主体を要求する。Cognito ログイン時に利用者や管理権限を自動作成する構成は採用していない。

Codex Cloud で実接続するには、コンテナ内 JVM から User Pool の issuer ホスト（公開鍵取得）と Managed Login ホスト（トークン交換）への HTTPS 通信が必要。制限付きの環境では両方を許可先に追加し、環境で指定されたプロキシと証明書の信頼設定を JVM に適用する。ログイン画面がブラウザーに表示されるだけでは、コンテナからの通信は確認できない。
