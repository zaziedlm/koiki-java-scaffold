# Cognito OIDC 実装の引継ぎ

2026-11-26。対象は `zaziedlm/koiki-java-scaffold` の作業ブランチ `docs/cognito-oidc-requirements`。起点は `55c78ac`（`main`）。次の作業は、ローカル PC の VS Code と作成済みの東京リージョンの検証用 User Pool を使った実接続確認。

## 設計と実装

| 領域 | 採用した方式 |
| --- | --- |
| 認証 | Spring Boot 管理の OAuth2 Client と Spring Security の `oauth2Login` を使用。Cognito Managed Login、Authorization Code、PKCE S256、`openid` スコープで構成。認可開始・コールバック・エラー画面・サインアウト画面などをアプリのチェーンに明示し、KOIKI の未一致ルート拒否、CSRF、標準セキュリティヘッダーを維持。 |
| 利用許可と権限 | 検証済みの `(iss, sub)` から既存の `FrameworkUserId` への対応だけをアプリの Flyway テーブル `scaffold_cognito_identity_link` に保存。利用状態と権限は KOIKI の公開 `IdentityQuery.findById()` で取得。未登録・無効のユーザーは拒否し、メールアドレスや Cognito グループで権限を自動付与しない。 |
| セッションと監査 | アプリ用の認証主体を既存の JDBC セッションに保存し、KOIKI ユーザー ID で索引付け。Cognito トークンは保存しない。ログイン結果、ログアウト、対応付けを監査する。ログアウトは CSRF 保護された POST でローカルセッションと Cookie を破棄した後、Cognito の `/logout` に遷移する。 |
| 実行モード | 既定モードは従来のローカル認証。`cognito` は OIDC 検証用。`aws` は Cognito モードを有効化し、HTTPS の公開オリジンと Secure Cookie を要求する。対応付けは運用者が `cognito-link` プロファイルの非 Web コマンドで実行する。 |

実装の入口は [CognitoSecurityConfiguration.java](../../src/main/java/com/example/scaffold/CognitoSecurityConfiguration.java)、対応付けは [CognitoIdentityLinks.java](../../src/main/java/com/example/scaffold/CognitoIdentityLinks.java) と [CognitoLinkConfiguration.java](../../src/main/java/com/example/scaffold/CognitoLinkConfiguration.java)。初期要件と AWS 運用上の論点は[要件書](cognito-oidc-requirements.md)に記載。

## Codex Cloud での検証結果

| 確認項目 | 結果 |
| --- | --- |
| Maven Wrapper `verify` | **15件成功、失敗・エラー・スキップ各0**。既存の13件に Cognito の実 HTTP コールバック統合テスト2件を追加。 |
| OIDC 統合テスト | トークン交換と JWT デコードをテスト用に差し替え、PKCE の認可リクエスト、state／nonce 不一致の拒否、未登録・無効ユーザーの拒否、セッション ID の更新、ホーム画面の権限表示、JDBC セッションのログアウトとユーザー ID 指定の失効を確認。Cognito の実トークン交換と署名鍵の取得はローカルで確認する。 |
| Docker | イメージをビルドし、分離した PostgreSQL で初期ユーザー作成と対応付けコマンドを実行。コンテナ公開先 `18080` でサインアウト画面と、PKCE S256 を含む認可リダイレクトを確認。 |
| Cloud 固有の制約 | 予約ポート `8080` を避け、Cloud 側 `127.0.0.1:18080` をコンテナ内 `8080` に接続。Cloud の制限付きプロキシは issuer ホストと Managed Login ホストへの HTTPS CONNECT に **403** を返した。実行環境には App Client Secret も設定していなかったため、実 Pool でのログイン・ログアウトは未実施。 |

## VS Code の PowerShell 7 で再開する

1. 作業ブランチの変更をローカルに取り込み、VS Code でリポジトリを開く。この引継ぎと一緒に提供するパッチを使う場合は、起点 `55c78ac` のクリーンなチェックアウトから作業ブランチを作り、先に `git apply --check <パッチのパス>`、続いて `git apply <パッチのパス>` を実行する。既に変更を取り込んでいる場合は二重適用を避ける。
2. JDK 21、PowerShell 7、Docker と Docker Compose v2（`!override` 対応の 2.24.4 以降）を用意する。[ローカル準備手順](local-setup-guide.md)に従い、リポジトリルートで次を実行する。既存の KOIKI ユーザーを使う場合は、その内部 ID を控え、`CreateAdmin` は省略する。`CreateAdmin` は空の検証 DB 専用。

   ```powershell
   pwsh ./tools/run-local.ps1 -Action Prepare
   pwsh ./tools/run-local.ps1 -Action CreateAdmin
   docker compose --profile app build app
   ```

3. AWS コンソールで検証用 Pool の **issuer URI、Managed Login ドメイン、App Client ID、テストユーザーの sub** を確認する。Pool は東京（`ap-northeast-1`）。対象クライアントは Cognito User Pool、Authorization code grant、`openid` を設定済み。コールバックは `http://localhost:18080/login/oauth2/code/cognito`、サインアウト先は `http://localhost:18080/signed-out`。利用するブラウザーも `localhost` で開く。
4. 次の値を設定し、アプリ所有の対応表にテストユーザーを登録する。`LINK_USER_ID` は `CreateAdmin` が表示する UUID。すべてのコマンドが成功してから次へ進む。

   ```powershell
   $env:SCAFFOLD_COGNITO_ISSUER = 'https://cognito-idp.ap-northeast-1.amazonaws.com/<user-pool-id>'
   $env:SCAFFOLD_COGNITO_DOMAIN = 'https://<managed-login-domain>'
   $env:SCAFFOLD_COGNITO_CLIENT_ID = '<app-client-id>'
   $env:SCAFFOLD_COGNITO_LINK_SUBJECT = '<cognito-user-sub>'
   $env:SCAFFOLD_COGNITO_LINK_USER_ID = '<framework-user-id>'

   docker compose --profile app run --rm -e SCAFFOLD_COGNITO_ISSUER -e SCAFFOLD_COGNITO_LINK_SUBJECT -e SCAFFOLD_COGNITO_LINK_USER_ID app --spring.main.web-application-type=none --spring.profiles.active=cognito-link
   ```

5. App Client Secret は保管先から取り出し、対話入力でコンテナに渡す。検証用上書きファイルがホストの `18080` を使うため、従来の `StartContainer` の代わりに次の Compose コマンドを使う。Secret、検証ユーザーのパスワード、認可コード、トークンを引継ぎ文書、Git、検証ログへ保存しない。

   ```powershell
   $cognitoSecret = Read-Host 'Cognito App Client Secret' -AsSecureString
   try {
       $env:SCAFFOLD_COGNITO_CLIENT_SECRET = [pscredential]::new('cognito', $cognitoSecret).GetNetworkCredential().Password
       docker compose -f compose.yaml -f compose.cognito.yaml --profile app up -d --wait app
   } finally {
       Remove-Item Env:SCAFFOLD_COGNITO_CLIENT_SECRET -ErrorAction SilentlyContinue
       $cognitoSecret.Dispose()
   }
   ```

6. ブラウザーで `http://localhost:18080/` を開く。初回ログインで「パスワードを強制的に変更」が表示された場合は仮パスワードから変更する。対応付けた内部 ID と権限がホームに表示され、ログアウト後は `/signed-out` に戻り、古いアプリセッションでホームを開けないことを確認する。可能なら、Cognito に存在しても未対応付けの別ユーザーで利用拒否も確認する。検証後は `docker compose --profile app stop app` で停止し、DB と対応付けを保持する。

### 実接続の切り分け

- **Cognito に遷移しない**場合は、Docker のアプリ起動ログ、公開ポート、VS Code が実行しているシェルの設定を確認。
- **Cognito がリダイレクトを拒否する**場合は、App Client に登録した URL と実際のスキーム、ホスト、ポート、パスを照合。サインアウト URL も今回の検証では `http://`。
- **Cognito ではログインできるがアプリが拒否する**場合は、コンテナからの issuer／Managed Login への HTTPS 通信、クライアント設定、対応表の issuer／sub、KOIKI ユーザーの状態を順に確認。ログには認可コードやトークンを載せない。

## Windows PC での実接続結果

2026-11-27。対象コミットは `3bcd56b`。東京リージョンの検証用 User Pool `koiki-scaffold-dev` と、Docker Compose の `http://localhost:18080/` を使用した。

| 確認項目 | 結果 |
| --- | --- |
| ローカルビルド | JDK 21 の Maven Wrapper `verify` で15件成功。Docker イメージのビルドも成功。 |
| ユーザー対応付け | 検証ユーザーを、既存の PostgreSQL に登録済みの有効な KOIKI 管理者に `cognito-link` で対応付けた。 |
| 実ログイン | Cognito を経由してホームに戻り、既存の KOIKI ユーザー ID と `SCAFFOLD:ADMIN` を確認した。 |
| ログアウト後の再アクセス | ログアウト後にホームを開くと、Cognito のサインイン入力画面が再表示された。`/signed-out` 単体の HTTP 200 は確認済み。ブラウザーでの戻り先 URL の個別記録、旧 Cookie の再送、未対応付けユーザーでの実 Pool の拒否確認は未実施。 |

初回は Cognito からアプリに戻った後、アプリのログイン失敗画面が表示された。コンテナ内 Java の HTTPS 診断で、公開鍵取得は成功し、Managed Login のトークン交換先では社内 HTTPS プロキシ経由の証明書チェーンが `PKIX` エラーになった。チェーンの指紋を Windows に配布済みの CA と照合し、コンテナの既定 `cacerts` を複製した `jssecacerts` に Windows の信頼ストアから取り出したルート CA を追加した。アプリを再起動し、TLS 検証を有効にした状態で両方の接続先から HTTP 応答を受け取れることを確認した。この対応は現在の検証用コンテナに限られ、コンテナを再作成すると再適用が必要。

## AWS 配置前に残る判断

AWS 検証用の公開 HTTPS URL と専用コールバック／戻り先を決定し、App Client Secret の供給方法を設定する。空の AWS DB では、最初の KOIKI 管理者を作成し、Cognito ID を対応付ける運用が必要。Cognito 側だけでユーザーを無効化した際の既存アプリセッションの失効方法と許容時間も決める。
