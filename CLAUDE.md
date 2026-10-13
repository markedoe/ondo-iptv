# Règles du dépôt Ondo

Ce dépôt est **public**. Rien de privé ne doit y entrer, ni dans les fichiers, ni dans les messages de
commit, ni dans les captures.

- **Secrets** : clés App Store Connect (`.p8`), Key ID, Issuer ID, Team ID, keystore Android (`.jks`) et
  ses mots de passe, JSON du compte de service Google Play, jetons… vont uniquement dans les secrets GitHub
  (`Settings → Secrets and variables → Actions`, voir `docs/RELEASE.md` et `docs/RELEASE-ANDROID.md`).
  Ne jamais les écrire dans un fichier du dépôt, un commit, un log ou un artefact de CI.
- **Vie privée** : aucune adresse e-mail personnelle (seulement les adresses `noreply`), aucun nom réel,
  aucun chemin de dossier personnel, aucune adresse IP de réseau local, aucune donnée d'abonnement IPTV
  réelle (URL de panel, identifiants). Les tests et la démo utilisent `example.com` et des flux publics.
- **Neutralité** : ne pas citer d'autres applications IPTV ; Ondo est un lecteur, il ne fournit aucun
  contenu ; ne jamais employer « free » / « gratuit » pour qualifier l'IPTV.
- **Garde-fou** : `.claude/hooks/leak_guard.py` est branché en hook `PreToolUse` (`.claude/settings.json`) :
  il bloque l'écriture de secrets ou de données personnelles dans le dépôt et vérifie les changements avant
  `git commit` / `git push`. La CI le relance sur tout l'historique avec gitleaks. En cas de faux positif,
  ajouter une regex à `.leakguard-allow` plutôt que de contourner le hook.
  Le garde-fou vérifie aussi l'adresse `git config user.email` (adresse `noreply` uniquement). Il ne lit
  pas le contenu des fichiers binaires : avant d'ajouter une image, vérifier qu'elle ne contient pas de
  métadonnées personnelles (position GPS, nom d'appareil).
