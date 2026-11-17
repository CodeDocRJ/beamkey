# Beamkey: Hostinger, Cloudflare, GitHub and app setup

Order matters. Do the parts in order: A (copy files) -> B (Hostinger) -> C (Cloudflare DNS) -> D (deploy) -> E (GitHub) -> F (app).

Facts used below
- Domain: getbeamkey.com (registered at Hostinger, DNS on Cloudflare)
- Worker name in Cloudflare: room-relay (must stay the same)
- Deploy repo: https://github.com/CodeDocRJ/beamkey-web
- Workspace folder: D:\_Projects\RECEIVE PASS\CLAUDE

## A. Put the new files in your workspace
1. Close Android Studio and stop `npm run dev` if running.
2. Extract the zip. Open the extracted `CLAUDE` folder, select everything inside, copy it into `D:\_Projects\RECEIVE PASS\CLAUDE` and choose "Replace the files in the destination". Your `.git`, `node_modules` and `package-lock.json` stay untouched.
3. In PowerShell:
   ```
   cd "D:\_Projects\RECEIVE PASS\CLAUDE\room-relay"
   npm install
   ```

## B. Hostinger (registrar only)
You only use Hostinger to hold the domain. Do not buy or keep a Hostinger hosting plan for this project (if one is in your account, cancel it within the 30-day refund window).
1. hPanel -> Domains -> getbeamkey.com. Confirm the nameservers are the two Cloudflare ones (they end in `.ns.cloudflare.com`). Your Cloudflare screenshot already shows the domain active, so this is normally done.
2. In the same domain page, make sure DNSSEC is OFF at Hostinger (if it is on while Cloudflare runs the DNS, the domain stops resolving for many users).
3. Keep auto-renew on, WHOIS privacy on, domain lock on. Turn on two-factor authentication for your Hostinger login.

## C. Cloudflare DNS (this is why you saw Hostinger's default page)
Cloudflare copied Hostinger's "parking page" records into your DNS. They must go before the Worker can take the name.
1. dash.cloudflare.com -> getbeamkey.com -> DNS -> Records.
2. Delete every `A`, `AAAA` and `CNAME` record whose Name is `getbeamkey.com` (shown as `@`) or `www`. Leave `MX`, `TXT`, `NS` records alone.
3. SSL/TLS -> Overview: set the mode to **Full**. SSL/TLS -> Edge Certificates: turn on **Always Use HTTPS** and **Automatic HTTPS Rewrites**; Minimum TLS version 1.2.
4. Network: make sure **WebSockets** is ON (default).
5. Security -> Bots: if **Bot Fight Mode** is on and the app later gets blocked (see Troubleshooting), turn it off or add an exception for `/api/` and `/ws/`.
6. Turn on two-factor authentication for your Cloudflare login.

## D. First deploy from your PC
```
cd "D:\_Projects\RECEIVE PASS\CLAUDE\room-relay"
npx wrangler login        (only if it says you are not logged in)
npx wrangler deploy
```
`wrangler.toml` already contains the routes for getbeamkey.com and www.getbeamkey.com, so this one command uploads the code and creates the DNS records and HTTPS certificates. Expected end of output: the workers.dev URL plus `getbeamkey.com (custom domain)` and `www.getbeamkey.com (custom domain)`.

If it says a hostname "already has externally managed DNS records", go back to step C2 and delete the leftover record, then deploy again.

Check (allow a few minutes for the certificate; use a private window):
1. https://getbeamkey.com/api/health  ->  {"ok":true}
2. https://getbeamkey.com  ->  the Beamkey page with a pairing-code box
3. Cloudflare -> Workers & Pages -> room-relay -> Settings -> Domains & Routes lists getbeamkey.com, www.getbeamkey.com and the workers.dev address.

## E. GitHub (backup repo + deploy repo)
One-time, only if not done yet:
```
cd "D:\_Projects\RECEIVE PASS\CLAUDE"
git remote -v
```
You should see `origin` (your main backup repo) and `deploy` (https://github.com/CodeDocRJ/beamkey-web.git). If `deploy` is missing: `git remote add deploy https://github.com/CodeDocRJ/beamkey-web.git`.

Every time you change something:
```
cd "D:\_Projects\RECEIVE PASS\CLAUDE"
git add .
git commit -m "Beamkey: production domain, app URL, branding"
.\publish.ps1
```
`publish.ps1` pushes everything to `origin` and only the `room-relay` folder to `beamkey-web`. If PowerShell refuses to run scripts, use:
`powershell -ExecutionPolicy Bypass -File .\publish.ps1`

Automatic deploys (one time, after the first manual deploy worked):
1. Cloudflare -> Workers & Pages -> room-relay -> Settings -> Builds -> Connect.
2. Choose GitHub, repository `beamkey-web`, branch `main`, root directory `/`.
3. Build command: leave empty. Deploy command: `npx wrangler deploy`.
4. From now on every `.\publish.ps1` that changes `room-relay` redeploys the site. Build logs are under Deployments.

Never edit files directly in `beamkey-web` on GitHub. It is a mirror and the next publish would be rejected.

## F. Android app
1. Open `KeyRelay-android` in Android Studio, File -> Sync Project with Gradle Files.
2. The app's application ID is now `com.beamkey.app`, so it installs as a NEW app named Beamkey. Uninstall the old KeyRelay app from the phone first.
3. Run on your phone. The server address is https://getbeamkey.com (debug and release). In the app: Settings tab should show `getbeamkey.com`; the Pair Laptop tab should show an 8-character code within a few seconds.
4. Open https://getbeamkey.com on the laptop, type the code, compare the check code on both screens, tap Approve on the phone, then Send.

Testing against your own PC (optional, debug build only): run `npm run dev` in `room-relay`, then in the app Settings type `http://10.0.2.2:8787` (emulator) or `http://<PC-IP>:8787` (real phone, started with `npx wrangler dev --ip 0.0.0.0`). `10.0.2.2` does NOT work on a real phone. Set it back to `https://getbeamkey.com` afterwards.

## Troubleshooting
- App stuck on "Connecting to relay": the app shows the server name under the status. If it is not `getbeamkey.com`, open Settings, type `https://getbeamkey.com`, Save. Then open https://getbeamkey.com/api/health in the phone's browser; it must show {"ok":true}.
- Health works in a browser but the app gets 403: Cloudflare Bot Fight Mode is challenging the app. Turn it off (Security -> Bots) or add a WAF custom rule that skips bot checks for URI paths starting with `/api/` and `/ws/`.
- The site still shows Hostinger's page: DNS records from step C2 are still there, or your browser cached the old page. Delete them, redeploy, test in a private window.
- `git subtree push` says "rejected" / "non-fast-forward": someone edited `beamkey-web` directly. If nothing there is worth keeping, overwrite it from your workspace:
  ```
  $sha = git subtree split --prefix=room-relay main
  git push deploy "${sha}:main" --force
  ```
- Build fails in Cloudflare with a name mismatch: the `name` in `room-relay/wrangler.toml` must be `room-relay`, the same as the Worker in Cloudflare.
- The website loads but the code is refused with "bad origin": you opened the site through an address other than getbeamkey.com / www.getbeamkey.com / the workers.dev URL via a proxy or iframe. Open the plain address.
- Work laptop cannot open the site: some company filters block newly registered domains. Test the workers.dev address on that laptop too, and ask IT to allow getbeamkey.com if needed.
