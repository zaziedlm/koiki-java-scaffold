# koiki-java-scaffold Agent Guidance

KOIKI-JavaWeb-FW（Framework）を採用するSpring MVCアプリケーションの土台repositoryです。

## 役割と境界

- このrepositoryはアプリケーション（Customer側）の成果物です。Frameworkの検証・承認手続（Framework repositoryの`AGENTS.md`）は適用しません。
- Framework repository（`zaziedlm/KOIKI-JAVAWEB`）は**参照のみ**です。commit・push・PR・Issue作成をしません。Frameworkの不具合・不足は報告にとどめます。
- Frameworkは固定版の配布物（Release ZIP）から取得します。Framework source、Root Reactor、`relativePath`、GitHub Packagesに依存しません。
- 方針の正本は`docs/policy/koiki-framework-adoption-policy.md`です。

## Frameworkの使い方

- `org.koikifw.*.internal`、FrameworkのJPA Entity／Repositoryを参照しません。Starter、Public API（例：`IdentityQuery`、`FrameworkPrincipal`）を使います。
- Framework migrationのSQLをcopyしません。アプリのmigrationは`db/migration/scaffold`に置きます。
- `SecurityFilterChain`は対象routeを明示し、KOIKI fallback（未一致routeを拒否）より高い優先順位にします。CSRFと標準Security Headerを無効化しません。
- secret（DB password、HMAC key等）をsource・log・testの共有値に置きません。test用の値はtest内に閉じます。
- Referenceアプリの業務class・migration・seed・demo userをcopyしません。参照して設計を学ぶことは構いません。

## 作業手順

1. 初回は`pwsh ./tools/prepare-koiki-distribution.ps1`でFrameworkを取り込みます。
2. 変更後は`./mvnw verify`を実行します（Docker必須）。container関連を変えた場合は`docker build`も確認します。
3. Framework版の更新は`pom.xml`と`tools/koiki-distribution.properties`を同じ変更で行います。`tools/prepare-koiki.ps1`は配布物同梱版のレビュー済みcopyであり、直接編集しません。
