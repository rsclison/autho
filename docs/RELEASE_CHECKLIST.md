# Release checklist

This project keeps the compiled administration UI in `resources/public/admin` so the Clojure server can serve `/admin/ui` without a separate Node process. For release builds, regenerate and commit those assets from a clean working tree.

## Required checks

Run the consolidated release check:

```bash
./scripts/check-release.sh
```

It executes:

- `./lein test`
- `npm run lint`
- `npm test`
- `npm run build`

The Leiningen test profile uses an in-memory H2 policy database via `-Dautho.policy.db.path=mem:autho-policy-test;DB_CLOSE_DELAY=-1`, so tests must not modify `resources/h2db.mv.db`.

## Demo scenario

Start the server with explicit secrets:

```bash
export JWT_SECRET="demo-jwt-secret-32-characters-minimum"
export API_KEY="demo-api-key-32-characters-minimum"
./lein run
```

In another shell:

```bash
API_KEY="demo-api-key-32-characters-minimum" ./examples/commercial_demo.sh
```

For Kafka/LDAP demonstrations, start the support stack first:

```bash
cd docker
docker compose up -d
```

## API policy

Public integration documentation should prefer `/v1/*` endpoints. Historical endpoints such as `/isAuthorized`, `/whoAuthorized`, `/whatAuthorized`, `/policy/:class`, and `/admin/*` remain compatibility endpoints unless explicitly removed in a future major release.
