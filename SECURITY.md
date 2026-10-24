# Security

**English** · [Français](#sécurité-français)

## Reporting a vulnerability

Please **do not open a public issue**. Use GitHub's private reporting: the **Security → Report a
vulnerability** tab of this repository. Say which platform (iPhone, iPad, Mac, Android), the app version and
the steps to reproduce.

Reports get a first answer within 7 days. A published fix is mentioned in the release notes, crediting you if
you agree.

## Scope

- The Ondo apps (iOS, iPadOS, macOS, Android) and the code of this repository.
- Ondo is a player: it provides no content and runs no online service (on iPhone and iPad it only serves MKV
  films to the system player on 127.0.0.1, inside the device). On Android, while Sync on this network is on
  and Ondo is open, it answers sync sessions from the local network on a TCP port ([docs/SYNC.md](docs/SYNC.md)):
  in scope. Problems with an IPTV provider or a third-party playlist are out of scope.

## Supported versions

Only the latest published version receives security fixes.

---

# Sécurité (français)

## Signaler une vulnérabilité

Merci de **ne pas ouvrir d'issue publique**. Utilisez le signalement privé de GitHub :
onglet **Security → Report a vulnerability** de ce dépôt. Indiquez la plateforme (iPhone, iPad, Mac,
Android), la version de l'app et les étapes pour reproduire.

Les signalements reçoivent une première réponse sous 7 jours. Un correctif publié est mentionné dans les
notes de version, avec votre accord pour vous citer.

## Périmètre

- Les apps Ondo (iOS, iPadOS, macOS, Android) et le code de ce dépôt.
- Ondo est un lecteur : il ne fournit aucun contenu et ne fait tourner aucun service en ligne (sur iPhone et
  iPad, il sert seulement les films MKV au lecteur du système sur 127.0.0.1, à l'intérieur de l'appareil).
  Sur Android, tant que Synchroniser sur ce réseau est activé et qu'Ondo est ouvert, il répond sur un port TCP
  aux sessions de synchronisation venant du réseau local ([docs/SYNC.fr.md](docs/SYNC.fr.md)) : dans le
  périmètre. Les problèmes d'un fournisseur IPTV ou d'une playlist tierce sont hors périmètre.

## Versions prises en charge

Seule la dernière version publiée reçoit des correctifs de sécurité.
