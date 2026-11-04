# Runners Linux auto-hébergés

*[English version](RUNNERS.md)*

Les jobs de test Linux peuvent tourner sur notre propre machine Linux (un VPS) au lieu de celles de GitHub. Rien ne
change tant que la variable de dépôt `LINUX_RUNNER` n'est pas définie : sans elle, chaque job tourne sur les
machines de GitHub, exactement comme avant.

Trois règles, vérifiées en CI par `.github/scripts/check_runners.py` (ci.yml et security.yml) :

- **Seulement nos propres lancements.** Un job n'arrive sur le VPS que pour un push sur nos branches, un lancement
  à la main (`workflow_dispatch`) ou une planification. Une pull request (`pull_request`, `pull_request_target`),
  d'un fork ou non, tourne toujours sur les machines de GitHub, quoi que dise la variable.
- **Aucun secret de publication sur le VPS.** Un job qui lit un secret autre que le `GITHUB_TOKEN` du run, passe
  `secrets: inherit` ou utilise un `environment:` reste sur les machines de GitHub.
- **Un seul chemin.** Le `runs-on` d'un job est soit une étiquette GitHub (`ubuntu-…`, `macos-…`, `windows-…`),
  soit exactement cette expression, copiée à l'octet près :

  ```yaml
  runs-on: ${{ fromJSON((github.event_name == 'push' || github.event_name == 'workflow_dispatch' || github.event_name == 'schedule') && vars.LINUX_RUNNER || '"ubuntu-24.04"') }}
  ```

  Pour tout autre événement la condition est fausse et le job reçoit `"ubuntu-24.04"` ; pour nos propres
  lancements il reçoit la variable, ou `"ubuntu-24.04"` tant qu'elle est vide. Toute autre expression, variable,
  entrée, valeur de matrice, groupe de runners ou étiquette `self-hosted` dans `runs-on` fait échouer la
  vérification.

## Ce qui peut tourner sur le VPS

| Workflow | Job | Remarques |
| --- | --- | --- |
| android.yml | `core` (tests du cœur Kotlin) | Java par `setup-java` ; demande `python3` |
| android.yml | `app` (lint, tests unitaires et Compose, captures Roborazzi, bundle) | demande le SDK Android ; voir [Captures](#captures-roborazzi) |
| android.yml | `listing` (longueurs des textes Google Play) | `python3` seulement |
| ci.yml | `core-linux` (SwiftLint, vérifications de configuration, tests d'IPTVCore) | tourne dans le conteneur `swift:6.0-noble` : demande Docker |
| security.yml | `leaks` (leak guard, gitleaks), `neutrality` | `python3`, `git`, `curl`, `tar` |
| mutation.yml | `pitest` (hebdomadaire) | Java par `setup-java` |

Tous les autres jobs restent sur les machines de GitHub :

| Job | Pourquoi |
| --- | --- |
| release.yml, release-android.yml, deliver.yml, promote-android.yml, play-catchup.yml, play-status.yml, app-store-assets.yml, funding-pages.yml | secrets de publication (clé App Store Connect, keystore Android, compte de service Google Play, clé Stripe) |
| chaque job macOS (ci.yml `apple`, `release-build`, `ui-tests`, appstore-screenshots.yml, release.yml) | macOS |
| android.yml `screenshots`, `references` ; ci.yml `publish-screenshots` ; appstore-screenshots.yml `publish` ; awards.yml `refresh` | ils détiennent un jeton `contents: write` qui peut pousser sur le dépôt ; jobs courts, rien à gagner sur le VPS |
| pages.yml `deploy` | l'environnement `github-pages` et un jeton OIDC (`id-token: write`) |
| tv-release-check.yml `tv` | l'émulateur Android TV demande `/dev/kvm` (virtualisation imbriquée, que la plupart des VPS n'offrent pas) et `sudo` pour sa règle udev ; l'utilisateur du runner n'a pas `sudo` |
| playback-lab.yml `check` | installe libVLC et ffmpeg avec `sudo apt-get` |

Un nouveau job Linux qui doit utiliser le VPS prend l'expression ci-dessus ; la vérification le refuse s'il lit un
secret.

## Basculer

- **Vers le VPS :** Settings → Secrets and variables → Actions → onglet **Variables** → *New repository variable* :
  nom `LINUX_RUNNER`, valeur les étiquettes des runners en tableau JSON, par ex.
  `["self-hosted","linux","x64","ondo"]` (ou une chaîne JSON pour une seule étiquette : `"ondo"`). Après le passage à
  une organisation, la variable peut aussi être définie pour l'organisation ; la valeur du dépôt l'emporte.
- **Retour chez GitHub :** supprimer la variable (ou la vider). Les runs suivants prennent `ubuntu-24.04`.
- La valeur doit être du JSON valide : un `ondo` nu fait échouer chaque workflow avant son premier job.
- Les jobs attendent dans la file tant qu'aucun runner portant ces étiquettes n'est en ligne ; GitHub annule un job
  qui a attendu 24 heures. Si le VPS est en panne, supprimer la variable.

## Ce qu'il faut au VPS

L'image `ubuntu-24.04` de GitHub contient beaucoup d'outils ; les jobs déplacés n'installent que Java
(`setup-java`), Gradle (`setup-gradle`) et, dans leur conteneur, la chaîne Swift et `python3`. La machine doit avoir :

- **Ubuntu 24.04, x86_64** (le rendu natif de Robolectric fournit des bibliothèques Linux x86_64 compilées pour
  glibc), au moins 4 vCPU, 16 Go de mémoire et 60 Go de disque, comme la machine de GitHub.
- **Les dépendances du runner** (`./bin/installdependencies.sh` dans le dossier du runner), et `git` (2.18 ou plus,
  pour que `actions/checkout` clone avec git), `curl`, `ca-certificates`, `tar`, `unzip`, `python3` (3.10 ou plus).
- **Docker** pour les jobs `container:` (ci.yml `core-linux` tourne dans `swift:6.0-noble`). Préférer **Docker
  rootless** pour l'utilisateur du runner : être membre du groupe `docker` revient à être root sur la machine.
  Docker récupère `swift:6.0-noble` sur Docker Hub.
- **Le SDK Android** pour android.yml `app` : les outils en ligne de commande dans `ANDROID_HOME`, les licences
  acceptées (`sdkmanager --licenses`), la plateforme `android-37` (`compileSdk`, `android/gradle/libs.versions.toml`)
  et les build tools que demande le plugin Android Gradle, installés d'avance ou laissés au téléchargement par
  Gradle dans un dossier de SDK accessible en écriture.
- **L'environnement de la machine de GitHub**, dans le fichier `.env` du runner (lu à chaque démarrage) :
  `ANDROID_HOME=…`, `LANG=C.UTF-8`, `TZ=UTC` (des tests mettent en forme des dates et des nombres).
- **Pas de `sudo`** pour l'utilisateur du runner : aucun job déplacé ne s'en sert.

### Captures Roborazzi

android.yml `app` compare les captures aux références commitées (au plus 0,1 % de pixels différents,
`screenshots/Baselines.kt`). Elles sont dessinées par le rendu natif de Robolectric (`@GraphicsMode(NATIVE)`,
`robolectric.pixelCopyRenderMode=hardware`) : les polices viennent du jar Android de Robolectric, pas de la machine,
mais les bibliothèques de dessin natives tournent sur le processeur et la glibc de la machine, et l'anticrénelage
peut différer de quelques pixels. Avant de basculer pour de bon :

1. Définir `LINUX_RUNNER`, lancer *Android* à la main sur une branche de travail (`record_screenshots` décoché) et
   lire le résultat : réussi, les références tiennent aussi sur le VPS.
2. Si des captures diffèrent, ouvrir l'artefact **android-screenshot-diffs**. Différences seulement au bord des
   lettres : lancer *Android* à la main avec `record_screenshots` sur cette branche (les tests tournent sur le VPS ;
   le commit est fait par le job `references`, qui reste sur les machines de GitHub), puis le relancer sans
   enregistrer sur le VPS **et** une fois la variable supprimée, sur les machines de GitHub : les deux doivent
   réussir, pour que le retour en arrière reste possible. Différences plus grandes : garder le job chez GitHub et en
   chercher d'abord la cause.

## Durcissement

Le dépôt est public : n'importe qui peut ouvrir une pull request, et un runner auto-hébergé exécute le code
qu'un job récupère. L'expression ci-dessus tient toutes les pull requests loin du VPS ; ces réglages assurent le
reste.

- **Runners éphémères : un job par runner.** Chaque runner s'enregistre pour un seul job, l'exécute et est
  supprimé ; son dossier est effacé avant le suivant. Rien de ce qu'un job laisse (fichiers, processus, caches,
  identifiants) n'est vu par le job suivant. Utiliser des runners just-in-time (ci-dessous) ou
  `config.sh --ephemeral`.
- **Un groupe de runners pour ce seul dépôt.** Réglages de l'organisation → Actions → Runner groups → *New runner
  group* (par ex. `ondo`) : *Repository access: Selected repositories* → `ondo-iptv` seulement ; cocher **Allow
  public repositories** (décoché par défaut, nécessaire puisque le dépôt est public). *Workflow access* peut être
  limité à certains workflows.
- **Approbation des pull requests venues de l'extérieur.** Settings → Actions → General → *Fork pull request
  workflows from outside collaborators* → **Require approval for all outside collaborators** (dans les réglages de
  l'organisation aussi). Les pull requests tournent déjà sur les machines de GitHub ; ceci empêche en plus le
  workflow d'un inconnu de tourner avant que quelqu'un l'ait lu.
- **Jeton en lecture seule par défaut.** Chaque workflow commence par `permissions: contents: read` ; un job qui
  écrit le demande pour lui seul, et ces jobs restent chez GitHub. Settings → Actions → General → *Workflow
  permissions* → **Read repository contents and packages permissions**, et *Allow GitHub Actions to create and
  approve pull requests* décoché.
- **Aucun jeton durable accessible aux jobs.** Aucun secret de publication n'arrive sur le VPS (vérifié en CI).
  Enregistrer un nouveau runner pour chaque job demande un identifiant capable de créer des runners : utiliser une
  **GitHub App** de l'organisation avec la seule permission *Self-hosted runners: Read and write* (organisation),
  qui ne peut lire ni le code, ni les secrets, ni les réglages. Sa clé privée n'est lisible que par root et sert au
  superviseur ci-dessous, jamais à l'utilisateur du runner ; chaque job reçoit une configuration de runner à usage
  unique, pas la clé. Ne pas utiliser de jeton d'accès personnel.
- **Une machine dédiée**, tenue à jour (mises à jour automatiques), SSH par clé seulement, rien d'autre installé ni
  stocké dessus.
- **Réseau.** Rien n'écoute à part SSH (le runner n'ouvre que des connexions sortantes). Filtrer la sortie si
  possible : autoriser le DNS et le HTTPS sortant (et le HTTP pour les paquets Ubuntu dans les conteneurs) ; refuser
  l'adresse de métadonnées de l'hébergeur, les plages d'adresses privées (RFC 1918) du réseau de l'hébergeur, le SMTP
  et le SSH sortant. Pour une liste stricte de domaines, placer devant un proxy HTTPS à liste blanche (`HTTPS_PROXY`
  dans `.env`) : GitHub (`github.com`, `api.github.com`, `*.actions.githubusercontent.com`,
  `objects.githubusercontent.com`, `codeload.github.com`, `ghcr.io`), Docker Hub, Maven Central, le Maven de Google
  (`dl.google.com`, `maven.google.com`), le portail de plugins et les distributions Gradle (`plugins.gradle.org`,
  `services.gradle.org`), l'archive d'Ubuntu et les hôtes des paquets Swift.

### Runners just-in-time avec systemd

Un service root demande à GitHub une configuration de runner à usage unique, exécute un job en tant
qu'utilisateur non privilégié `runner` dans un dossier neuf, et recommence. Les instances `ondo-runner@1`,
`ondo-runner@2`… exécutent plusieurs jobs à la fois.

`/usr/local/sbin/ondo-runner` (appartenant à root, mode 700) :

```sh
#!/bin/sh
# Un job : un runner à usage unique dans un dossier neuf, puis sortie (systemd lance le suivant).
set -eu
ORG="<organisation>"
GROUP_ID="<id du groupe de runners>"     # GET /orgs/<organisation>/actions/runner-groups
APP_ID="<id de l'app>"; INSTALLATION_ID="<id de l'installation>"
KEY=/etc/ondo-runner/app.pem            # root seulement, mode 600
DIR=/home/runner/job-$1

b64() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now=$(date +%s)
header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64)
claims=$(printf '{"iat":%s,"exp":%s,"iss":"%s"}' $((now - 60)) $((now + 540)) "$APP_ID" | b64)
jwt="$header.$claims.$(printf '%s' "$header.$claims" | openssl dgst -sha256 -sign "$KEY" | b64)"
token=$(curl -fsS -X POST -H "Authorization: Bearer $jwt" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/app/installations/$INSTALLATION_ID/access_tokens" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
jit=$(curl -fsS -X POST -H "Authorization: Bearer $token" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/orgs/$ORG/actions/runners/generate-jitconfig" \
  -d "{\"name\":\"ondo-$(hostname)-$1-$now\",\"runner_group_id\":$GROUP_ID,\"labels\":[\"self-hosted\",\"linux\",\"x64\",\"ondo\"]}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["encoded_jit_config"])')
unset jwt token

rm -rf "$DIR"
install -d -o runner -g runner "$DIR"
tar -xzf /opt/actions-runner.tar.gz -C "$DIR"   # la version du runner, vérifiée avec son SHA-256 publié
cp /etc/ondo-runner/env "$DIR/.env"
chown -R runner:runner "$DIR"
cd "$DIR"
runuser -u runner -- ./run.sh --jitconfig "$jit" || true
cd / && rm -rf "$DIR"
```

`/etc/systemd/system/ondo-runner@.service` :

```ini
[Unit]
Description=Ondo ephemeral GitHub Actions runner %i
After=network-online.target docker.service
Wants=network-online.target

[Service]
ExecStart=/usr/local/sbin/ondo-runner %i
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Puis `systemctl enable --now ondo-runner@1`. Avec Docker rootless, le Docker de l'utilisateur `runner` doit tourner
(`loginctl enable-linger runner`) et `DOCKER_HOST` être défini dans `/etc/ondo-runner/env`.

### Enregistrement à la main (un premier essai)

Réglages de l'organisation → Actions → Runners → *New runner* → *New self-hosted runner* → Linux, x64 : la page
donne les commandes de téléchargement et un jeton d'enregistrement valable une heure. En tant qu'utilisateur
`runner` :

```sh
./config.sh --url https://github.com/<organisation> --token <jeton de cette page> \
  --runnergroup ondo --labels ondo --ephemeral --unattended
./run.sh
```

Le runner prend un job et se désenregistre. Les étiquettes `self-hosted`, `Linux` et `X64` sont ajoutées
d'elles-mêmes (les étiquettes sont comparées sans tenir compte de la casse). D'ici le passage, un runner peut être
enregistré sur le dépôt de la même façon (Settings du dépôt → Actions → Runners), sans groupe de runners.

## Passage du dépôt à une organisation

À faire après le passage (rien n'est changé pour l'instant). GitHub redirige `github.com/markedoe/ondo-iptv/…`, ses
URL git et `raw.githubusercontent.com/markedoe/ondo-iptv/…` vers le nouveau propriétaire, **tant qu'aucun nouveau
dépôt ne reprend l'ancien nom** : ne jamais recréer `markedoe/ondo-iptv`, les apps déjà publiées comptent sur ces
redirections. GitHub Pages n'est **pas** redirigé.

**Pages : `https://markedoe.github.io/ondo-iptv/` devient `https://<organisation>.github.io/ondo-iptv/`.**

- Les apps publiées ouvrent l'ancienne adresse (pages d'assistance et de confidentialité) : la garder en vie avec
  un site utilisateur `markedoe/markedoe.github.io` contenant des pages de redirection `ondo-iptv/`, ou passer les
  deux à un domaine personnalisé.
- À mettre à jour : `.github/about.json` (homepage) ; `fastlane/metadata/*/support_url.txt`, `marketing_url.txt`,
  `privacy_url.txt` (chaque langue) ; `fastlane/metadata/review_information/notes.txt` (la playlist de revue) ;
  `site/review/ondo-review.m3u` et `site/review/README.md` / `README.fr.md` ; `funding/stripe.json` (image) ;
  `App/Features/Help/HelpView.swift` (`supportURL`) ; `android/app/src/main/kotlin/com/markedoe/ondo/ui/OndoApp.kt`
  (`PRIVACY_URL`, `SUPPORT_URL`) ; `tools/awards/generate_awards.py` (user agent) ; `docs/RELEASE.md`,
  `docs/RELEASE.fr.md`, `docs/RELEASE-ANDROID.md`, `docs/RELEASE-ANDROID.fr.md`.
- release.yml, release-android.yml et play-catchup.yml vérifient la playlist de revue à
  `https://$OWNER.github.io/ondo-iptv/…`, avec `OWNER` tiré de `GITHUB_REPOSITORY` : ils suivent le passage
  d'eux-mêmes, donc le site doit être publié sous l'organisation (et les notes de revue mises à jour) avant la
  prochaine version.

**`markedoe/ondo-iptv` écrit en toutes lettres** (redirigé, à mettre à jour dans le même changement) :

- README.md, README.fr.md : badges, lien des issues, images des captures (`raw.githubusercontent.com`).
- `App/Model/AppModel+Awards.swift`, `android/app/src/main/kotlin/com/markedoe/ondo/data/AwardsDownload.kt` :
  le téléchargement de `awards-data`.
- `App/Player/ProblemReportView.swift`, `android/core/src/main/kotlin/com/markedoe/ondo/core/playback/ProblemReport.kt`
  et son test `ProblemReportTest.kt` : le formulaire d'issue.
- `site/privacy.html`, `site/support.html`, `site/review/README.md`, `site/review/README.fr.md`.
- `docs/RELEASE.md`, `docs/RELEASE.fr.md` (le nom du dépôt).
- `.github/scripts/tests/test_leak_guard.py` : une URL d'exemple seulement ; elle peut rester.

**Workflows et scripts :** aucun n'écrit le nom du dépôt ; ils utilisent `GITHUB_REPOSITORY` / `github.repository`
(push des branches de captures et d'awards, `gh run list --repo`, `gh workflow run --repo`). Rien à changer.

**Aussi :**

- Installer la GitHub App de Claude sur l'organisation et lui donner `ondo-iptv` ; mettre à jour les dépôts que les
  sessions Claude peuvent utiliser.
- Vérifier que les secrets, variables, environnements (`github-pages`), réglages Pages et règles du dépôt sont là
  après le transfert ; appliquer aussi les réglages Actions du [Durcissement](#durcissement) à l'organisation.
- `fastlane/metadata/copyright.txt` et LICENSE nomment le propriétaire : ne les changer que si le propriétaire change.
- Ne jamais changer les identifiants `com.markedoe.ondo` (bundle ID, package Android, identifiants d'achats
  intégrés, packages Kotlin) : ce sont l'identité des apps dans les stores.
