# Focus Control — prototype Android

Projet Kotlin/Jetpack Compose destiné à un Galaxy S24 sous Android 16.

## Ouvrir et compiler
1. Installer Android Studio (version stable récente) et JDK 17.
2. Ouvrir le dossier `FocusControl` dans Android Studio.
3. Laisser Gradle synchroniser les dépendances.
4. Connecter le Galaxy S24 en USB avec le débogage USB activé.
5. Lancer la configuration `app`.

## Autorisations à activer
- Paramètres Android > Accessibilité > Applications installées > Focus Control.
- Paramètres > Accès spécial > Accès aux données d'utilisation > Focus Control.

## Important : état du prototype
Ce dépôt est un **squelette de développement**, pas une version de production ni un bloqueur fiable prêt à l'emploi.

Le moniteur événementiel détecte les changements d'application et déclenche un écran de blocage. Plusieurs éléments doivent être renforcés et testés sur le téléphone avant un usage réel :
- Le calcul de temps est ici un prototype basé sur les transitions de fenêtres ; il ne reconstitue pas encore précisément les périodes traversant veille, écran éteint, appels ou interruptions système.
- Le seuil de session doit être déclenché avec un suivi incrémental exact ; la version actuelle illustre la structure et nécessite cette correction avant usage quotidien.
- La persistance 24 h est un cycle glissant par application, mais la première version ne gère pas encore toutes les situations de changement d'heure, redémarrage pendant une session ou modification des limites en cours de cooldown.
- Android/Samsung peut suspendre le service d'accessibilité. Vérifier les réglages de batterie et tester après redémarrage.
- Les permissions d'utilisation et d'accessibilité doivent être accordées manuellement.
- Le prototype utilise SharedPreferences pour garder le projet léger ; une migration Room/DataStore est recommandée avant une version finalisée.

## Optimisations prévues
- Aucun polling de surveillance ni wake lock permanent.
- Traitement déclenché par événements d'accessibilité.
- Pas de réseau, publicité, analytics, GPS ou synchronisation.
- Rafraîchissement du compte à rebours uniquement lorsque l'écran de blocage est affiché.

## Confidentialité
Les noms d'applications, politiques et compteurs restent localement sur l'appareil. Aucune transmission réseau n'est implémentée.
