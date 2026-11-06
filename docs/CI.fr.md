# Intégration continue

*[English version](CI.md)*

Comment les workflows GitHub Actions sont organisés, ce qui décide des jobs lancés, ce qu'ils coûtent et ce qui reste à
faire. La publication est décrite dans [RELEASE.fr.md](RELEASE.fr.md) (Apple) et
[RELEASE-ANDROID.fr.md](RELEASE-ANDROID.fr.md) (Google Play).

## La règle : dans le doute, tout lancer

Un job n'est sauté que lorsqu'il est certain qu'il ne testerait rien de nouveau. Le moindre doute le lance : un chemin
inconnu, une recherche qui échoue, une branche sans différence avec `main`, pas de `main` à comparer. Le contrôle des
fuites et les contrôles de neutralité et de l'édition App Store (`security.yml`) tournent sur chaque push sur `main` et
chaque pull request. Le job Linux de la CI (`core-linux` : SwiftLint, `check_app_store.py` et les autres contrôles de
configuration, les tests du cœur Swift) tourne dans chaque run *CI*, jamais sauté par *What to run* ; mais un push ou
une pull request que le filtre de chemins de *CI* écarte ne lance aucun run *CI*, donc pas `core-linux` (`security.yml`
lance quand même `check_app_store.py`).

## Workflows

| Workflow | Quand | Jobs (machine) | Ce qu'il vérifie ou fait |
|---|---|---|---|
| *CI* (`ci.yml`) | push sur `main` et pull requests à travers son filtre de chemins ; à la main | *What to run* (Linux), *Lint + IPTVCore tests* (Linux), *Build iOS + macOS (Debug, App Store edition)* (macOS), *App tests — unit + UI* (macOS), *Publish screenshots* (Linux, push seulement) | SwiftLint, contrôles de configuration, tests du cœur Swift sur Linux et macOS, build debug macOS et son lancement, builds Release de l'édition App Store (iOS et macOS) et, sauf sur un push, de l'édition TestFlight (iOS), tests unitaires et UI de l'app sur un simulateur iPhone |
| *Android* (`android.yml`) | push sur `main` et pull requests à travers son filtre de chemins ; à la main (*record_screenshots*) | *What to run*, *Core (Kotlin) tests*, *App — lint, tests, screenshots, bundle*, *Google Play listing limits*, jobs de publication (tous Linux) | tests du cœur Kotlin, tests Robolectric et Compose, captures Roborazzi comparées aux références, lint, APK debug et bundle release, longueurs de la fiche |
| *Security* (`security.yml`) | chaque push sur `main`, chaque pull request, à la main | deux jobs Linux | garde anti-fuite et gitleaks sur chaque commit de chaque branche ; contrôles de neutralité, de l'édition App Store et de la playlist de review |
| *Deliver* (`deliver.yml`) | après un run *CI* ou *Android* vert d'un push sur `main` | Linux | lance *Release* (TestFlight : l'édition TestFlight pour les testeurs internes, l'édition App Store pour les testeurs externes) ou *Release Android* (test interne) quand ce que l'app embarque a changé |
| *Release* (`release.yml`) | tag `v*` ; à la main | *App Store screenshots* (appelé, macOS), *TestFlight / App Store Connect* (macOS), *TestFlight edition again* (Linux), *External testers (Beta App Review)* (Linux : il attend le traitement d'App Store Connect sans occuper un Mac) | voir RELEASE.fr.md |
| *App Store screenshots* (`appstore-screenshots.yml`) | appelé par *Release* ; à la main | un job macOS par appareil (iPhone, iPad, Mac), *Publish* (Linux) | prend les captures de la fiche en anglais et en français et les publie sur la branche `appstore-screenshots` |
| *Release Android*, *Promote Android*, *Play catch-up*, *Google Play status* | tag, *Deliver*, toutes les heures ou à la main | Linux | voir RELEASE-ANDROID.fr.md |
| *Website* (`pages.yml`) | push sur `main` touchant `site/` ; chaque semaine ; à la main | Linux | GitHub Pages |
| *Playback lab*, *TV release check*, *Mutation testing*, *Awards data*, *App Store header and search results*, *Support pages* | leurs chemins, leurs horaires ou à la main | Linux | voir l'en-tête de chaque workflow |

Chaque job macOS utilise le même Xcode (`DEVELOPER_DIR`, à relever dans chaque job macOS et dans RELEASE.fr.md à la fois).

## Quels jobs tournent

### Filtres de chemins

*CI* et *Android* filtrent les push et les pull requests avec une liste ordonnée de motifs, dans la syntaxe de GitHub :
`**` désigne n'importe quel chemin, `*` n'importe quel nom dans un dossier, `!` exclut, et le dernier motif qui
correspond l'emporte. Les listes se trouvent dans `.github/scripts/ci_scope.py` (`PATHS`) et sont recopiées à
l'identique dans les workflows ; `test_ci_scope.py` échoue si elles diffèrent.

- *CI* saute : `android/**`, `fastlane/metadata-android/**`, `site/**`, `docs/**` et les fichiers Markdown à la racine
  du dépôt.
- *Android* saute : `App/**`, `AppTests/**`, `UITests/**`, `MacUITests/**`, `Packages/**`, `project.yml`,
  `fastlane/metadata/**`, `site/**`, `docs/**` et les fichiers Markdown à la racine du dépôt.
- Tout le reste lance les deux. Les changements de workflows ou de scripts ne sont pas sautés. GitHub documente `**`
  comme correspondant à n'importe quel caractère, `/` compris, sans rien dire des noms qui commencent par un point : les
  deux listes incluent donc aussi `.*` et `.*/**` explicitement (`.github/`, `.claude/`, `.githooks/`, `.swiftlint.yml`).

**Chemins partagés : ils lancent toujours les deux plateformes.** Aucune liste ne doit les exclure :

- `fixtures/**` (`sync`, `film-versions`, `showcase`) : lus par les tests des deux cœurs et les captures des deux apps ;
- `docs/SYNC*` et `docs/PROFILES*` : les spécifications que suivent les deux cœurs ;
- `.github/**` (workflows et scripts), `.claude/**` ;
- `fastlane/Fastfile`, `fastlane/Appfile`, `fastlane/creative/**`, `Gemfile`, `Gemfile.lock` ;
- `funding/**`, `tools/**`, `LICENSE`, et tout dossier que les listes ci-dessus ne nomment pas.

**Quand apparaît un nouveau dossier lu par les deux apps, vérifiez qu'aucune liste ne l'exclut** et ajoutez un chemin
de ce dossier à `test_shared_folders_always_run_both` dans `test_ci_scope.py`.

### Un run lancé à la main sur une branche de travail

GitHub n'applique aucun filtre de chemins à un run lancé à la main (`gh workflow run ci.yml --ref <branche>`). Le
premier job, *What to run*, applique la même liste à chaque fichier que la branche a changé depuis sa base commune avec
`main` (`git diff --no-renames` : un fichier sorti de `App/` compte aussi) ; les fusions de `main` dans la branche ne
comptent pas. Quand aucun fichier changé n'est dans la liste, les jobs lourds (macOS pour *CI*, Kotlin et app pour
*Android*) sont sautés. Un run qui enregistre les captures de référence Android tourne toujours.

### Un push sur `main` déjà passé sur sa branche

Une branche testée à la main puis poussée sur `main` était testée deux fois. *What to run* cherche désormais un run
réussi du même workflow (un push sur `main` ou un run à la main ; jamais une pull request, qui teste un commit de
fusion) sur le même commit ou sur un commit au même arbre, dont tous les jobs lourds ont tourné et réussi et, pour
*Android*, qui n'a pas enregistré les captures de référence. S'il en existe un, les jobs lourds sont sautés ; sinon, ou
si les runs ne peuvent pas être lus, tout tourne.

### Ce que veut dire un job sauté

Un job sauté par son `if:` apparaît *skipped* ; GitHub le compte comme réussi pour les contrôles requis et le run se
termine en *success*, donc *Deliver* démarre quand même. C'est juste pour un push sur `main` dont l'arbre a déjà réussi.
Un run lancé à la main sur une branche n'atteint jamais *Deliver* (qui ne réagit qu'aux push sur `main`). *Release* ne
saute ses tests du cœur pour un build TestFlight que si un run de *CI* sur ce commit ou cet arbre a fait tourner et
réussir ses jobs macOS (`ci_scope.py passed ci`) : un run qui les a sautés ne compte pas. Si *What to run* échoue
lui-même, les jobs lourds sont sautés et le run est rouge, jamais vert.

### Simulation

Le seul changement d'une branche de travail, et ce que lance un run à la main (`test_ci_scope.py` rejoue ces cas) :

| Changement | Jobs macOS de *CI* | Jobs Kotlin et app d'*Android* |
|---|---|---|
| `android/…` seulement | sautés | lancés |
| `App/…` ou `Packages/…` seulement | lancés | sautés |
| `fixtures/…` seulement | lancés | lancés |
| `docs/RELEASE.md` seulement | sautés | sautés |
| `.github/workflows/…` seulement | lancés | lancés |
| `docs/SYNC.md` seulement | lancés | lancés |

## Captures App Store

*App Store screenshots* ne tourne plus sur les push : rien ne lit ses captures entre deux releases. *Release* l'appelle
pour les runs qui envoient la fiche (un tag, cibles `release`, `all`, `metadata*`), et l'envoi l'attend. Un job par
appareil compile une fois (`build-for-testing`) et prend l'anglais et le français sur ce build
(`test-without-building`) : trois jobs macOS au lieu de six. Quand une capture iPhone ou iPad échoue, rien n'est
publié ; quand seule la capture Mac échoue, les captures Mac déjà sur la branche sont gardées à côté des nouvelles.

**La fiche doit correspondre au build** (CLAUDE.md) : quand une capture échoue, un tag et les cibles `release` et `all`
s'arrêtent à leur première étape (*App Store screenshots captured*), avant toute compilation, tout envoi et toute
soumission, avec une erreur qui dit de relancer les captures puis la release (*Re-run all jobs*). Seules les cibles
`metadata*`, qui ne soumettent rien, continuent avec les captures déjà sur la branche `appstore-screenshots` (un
avertissement le dit). Lancez-le à la main pour voir les captures avant une release.

Ce qui est perdu : un test de capture cassé à l'exécution se voit à la release plutôt qu'au push (la *CI* compile ce
test à chaque run, sans le lancer) ; la branche ne montre plus l'interface de chaque push (`ci-screenshots` la montre,
à partir des tests UI de l'app).

## Machines macOS

Le compte ne peut faire tourner qu'un nombre limité de jobs macOS à la fois (5 avec l'offre Free de GitHub : vérifiez
l'offre du compte). Un run *CI* prend deux jobs macOS (trois avant ce changement), *App Store screenshots* trois,
*Release* un. D'où la règle de travail : **au plus deux branches lancent *CI* à la fois**, ce qui laisse une machine
macOS à *Release* ou aux captures. GitHub démarre un job en quelques secondes tant qu'une machine est libre ; les longues
attentes viennent de cette règle.

## Mesures (4 novembre 2026, avant ce changement)

Les 40 derniers runs de chaque workflow :

| | *CI* | *Android* |
|---|---|---|
| Période couverte | 17 h | 3 h |
| Lancement | 28 à la main, 9 push sur `main`, 3 pull requests | 37 à la main, 3 push sur `main` |
| Annulés | 17 sur 38 terminés (45 %) ; 12 remplacés par un run plus récent de la même branche | 5 |
| Attente d'une machine | médiane 0 min, au plus 21 min | 0 min |
| Run vert, durée réelle | médiane 20 min | médiane 19 min |
| Jobs, médiane | `apple` 5,1 min, `release-build` 14,2, `ui-tests` 18,9, `core-linux` 2,0 | `app` 18,4, `core` 2,1 |
| Étapes les plus lentes | `xcodebuild test` 14 à 20 min, choix et démarrage du simulateur 2 à 6, chaque build Release 4 à 6 | tests Robolectric et Roborazzi 12,0, bundle 2,8, lint 1,9 |
| Minutes de jobs macOS | 1 741, dont 653 (37 %) dans des runs annulés | aucune |

*App Store screenshots* : 30 runs en 21 jours, six jobs macOS (environ 55 minutes de jobs) chacun ; ses propres jobs ont
attendu jusqu'à 50 min une machine, et les runs *CI* attendaient derrière. Sur 8 push sur `main` de l'échantillon, 3
étaient des commits déjà réussis sur leur branche. Les caches Gradle sont trouvés (environ 1,3 Go restaurés par run).

**Pour mesurer à nouveau** (le dépôt est public, l'API n'exige pas de jeton en lecture) :

```bash
repo=markedoe/ondo-iptv
gh api "repos/$repo/actions/workflows/ci.yml/runs?per_page=40" \
  --jq '.workflow_runs[] | [.id, .event, .conclusion, .head_branch, .created_at, .updated_at] | @tsv'
gh api "repos/$repo/actions/runs/<id du run>/jobs" \
  --jq '.jobs[] | [.name, .conclusion, .created_at, .started_at, .completed_at] | @tsv'
```

L'attente d'un job est `started_at − created_at`, sa durée `completed_at − started_at` ; les `steps` de chaque job
donnent la même chose pour ses étapes.

## Ce que fait ce changement, et pourquoi

| Changement | Pourquoi | Gain attendu |
|---|---|---|
| *What to run* applique le filtre de chemins aux runs lancés à la main sur une branche | la plupart des runs de branche sont lancés à la main, ce qui ignore `paths` | environ 43 minutes de jobs macOS par run sauté |
| Le filtre d'*Android* est la liste inverse (`fixtures/**`, scripts et workflows le lancent désormais) | un push ne touchant que les fixtures sautait les tests Android qui les lisent | aucun : un trou bouché |
| Un push sur `main` saute ses jobs lourds quand son commit ou son arbre a déjà réussi avec eux | 3 push sur `main` sur 8 étaient testés deux fois | environ 43 minutes de jobs macOS par push concerné |
| *App Store screenshots* au moment de la release, un job par appareil | environ 75 minutes de jobs macOS par jour, et des machines prises à *CI* | l'essentiel ; environ 40 minutes de jobs par release |
| `apple` fusionné dans le job de build | un run *CI* prend deux machines macOS au lieu de trois | environ 2 min de préparation par run ; deux branches tiennent à côté d'une release |

## Pas encore fait, prêt à reprendre

| Proposition | Gain attendu | Risque | Comment |
|---|---|---|---|
| Démarrer le simulateur pendant le build (`ui-tests`) | 2 à 6 min de durée réelle par run | faible : `bootstatus` attend de toute façon | lancer `xcrun simctl boot` avant le build, `xcodebuild build-for-testing`, puis `bootstatus -b` et `test-without-building` |
| Lancer un run à la main une fois par lot de commits, ou passer par une pull request | l'essentiel des 37 % de minutes macOS perdues dans des runs annulés | aucun | pousser les commits, lancer *CI* une fois après le dernier ; ou ouvrir une pull request brouillon : filtres de chemins et annulation par branche s'appliquent d'eux-mêmes (ses runs ne comptent pas pour le saut des doublons) |
| Répartir les tests de l'app Android sur 2 ou 3 jobs Linux | durée réelle 19 → environ 10 min ; les minutes Linux sont gratuites ici | une classe de tests oubliée par les filtres | découper `:app:verifyRoborazziDebug` par filtres de tests, lint et bundle dans un job à part ; un contrôle que les comptes de tests des morceaux font le total |
| `cache-encryption-key` de Gradle | 10 à 20 s par run Android (cache de configuration réutilisé) | c'est un secret du dépôt : un job qui le lit ne peut pas tourner sur les machines auto-hébergées (docs/RUNNERS.md sur la branche des runners) | `openssl rand -base64 16` dans un secret, passé à `setup-gradle` comme `cache-encryption-key` |

## Écarté

- **Mettre DerivedData en cache** : le checkout donne une nouvelle date à chaque fichier, donc Xcode recompile quand
  même ; les paquets Swift sont déjà en cache (restaurés en 0,3 à 0,4 min).
- **Ne compiler l'édition App Store que sur `main` et les tags** : une casse propre à cette édition atteindrait `main`
  et bloquerait les livraisons TestFlight ; c'est le build que voit App Review (CLAUDE.md).
- **Changer les caches Gradle** : ils sont déjà trouvés ; le cache de build sert ce qui n'a pas changé.
- **Des machines Linux auto-hébergées en priorité** : les minutes Linux sont gratuites pour un dépôt public et Linux
  n'est pas le goulot ; leur gain est un cache Gradle chaud. Quand elles viendront, seuls nos propres lancements les
  utiliseront et aucun secret de publication ne les atteindra.
