# FriendConnect (PaperMC plugin)

Self-hosts [Espryra/friend-connect](https://github.com/Espryra/friend-connect) inside your Paper server.
Console / mobile / Windows Bedrock players add a Microsoft "host" account as a friend, see your
server in their friends list, tap it, and get redirected to your server.

The plugin bundles the friend-connect script, installs its dependencies on first start, launches it
as a child process, restarts it if it crashes, and stops it when the server stops.

## Requirements
- Paper 1.20+ (Java 17+; Paper 1.20.5+ needs Java 21)
- **Node.js 18.20 or newer** on the same machine (`node --version`)
- **Geyser** (Bedrock support) on this server or your proxy - FriendConnect only handles the
  friends list and the redirect; Geyser is what lets Bedrock clients actually play
- A **spare Microsoft account** to act as the host (do not use your personal account)
- Your Bedrock port (default UDP 19132) open in the firewall / hosting panel

## Build
```
mvn package
```
The jar is `target/FriendConnect-1.0.0.jar`. No JDK/Maven locally? Push this folder to GitHub -
the included workflow builds the jar for you (Actions > Build > Artifacts).

## Install
1. Put the jar in `plugins/` and start the server once.
2. Edit `plugins/FriendConnect/config.yml` - **only `server.ip` is required**.
3. Run `/friendconnect reload`.
4. The first run installs dependencies (about a minute), then prints a banner in the console:
   open the link, enter the code, sign in with the HOST account. One time only - the login is
   cached in `plugins/FriendConnect/runtime/lib/account/`.
5. When the console says `Friend Connect is running as "<gamertag>"`, players add that gamertag
   on Xbox / in Minecraft and join from the Friends tab.

## Commands (permission `friendconnect.admin`, default op; alias `/fc`)
| Command | What it does |
|---|---|
| `/fc status` | state, host gamertag, friends added, players redirected |
| `/fc auth` | show the Microsoft sign-in link/code again |
| `/fc start` / `/fc stop` | start or stop Friend Connect |
| `/fc restart` (or `reload`) | reload config.yml and restart |

## Troubleshooting
- **"Could not run node"** - install Node.js, or set `plugin.node-path` (common on panel hosts).
- **Install failed** - the machine needs internet access to `registry.npmjs.org` and `github.com`.
  Set `plugin.debug: true` to see full npm output.
- **Players see the server but can't connect** - `server.ip` must be your *public* address and the
  UDP port must be open; test that Bedrock can join Geyser directly first.
- **Sign-in code expired** - `/fc restart` prints a fresh one.

## Layout
```
src/main/java/...      plugin source
src/main/resources/    plugin.yml, config.yml, bundled node/dist (compiled script)
node/                  TypeScript source of the script (upstream code + config options)
```
To change the script: edit `node/src`, then in `node/` run `npm install` and
`npx tsc --outDir ../src/main/resources/node/dist`.

## Credits / license note
Script based on Espryra/friend-connect, which uses `bedrock-portal` and `prismarine-auth`.
The upstream repo had no license file when this was made, so check with its author before
redistributing publicly.
