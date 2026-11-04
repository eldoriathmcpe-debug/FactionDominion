FACTION DOMINION — PREMIER PROTOTYPE
Minecraft Java 1.20.1 / Paper / Java 17

CONTENU
- Plugin Maven prêt à compiler.
- /dominion give caveblock
- /dominion give finder_basic
- /dominion give finder_advanced
- /dominion give finder_elite

COMPILATION
1. Installer un JDK 17 et Maven.
2. Ouvrir un terminal dans le dossier du projet.
3. Exécuter : mvn package
4. Copier target/faction-dominion-0.1.0.jar dans le dossier plugins/ du serveur Paper 1.20.1.
5. Démarrer le serveur.

TESTS IMPORTANTS
- Le Cave Block est un prototype visuel : il remplace localement par de l'air les blocs opaques dans une zone autour du bloc posé. Les conteneurs (TileState), l'eau et la lave ne sont pas masqués.
- Dans cette première version, l'effet Cave Block est réservé au joueur qui a posé le bloc.
- L'Unclaim Finder compte les blocs conteneurs dans un cube autour du joueur, affiche 1% par conteneur (plafonné à 100%) et consomme une seconde d'utilisation par seconde où il est tenu en main.
- Les portées et l'affichage sont un prototype à tester sur un serveur de test avant ouverture publique.
- Le Finder ne sait pas encore distinguer les coffres appartenant à une faction ou situés en territoire claim/unclaim. Cette intégration dépendra du plugin Factions choisi.
- Le pack de ressources et les modèles définitifs ne sont pas inclus dans ce premier prototype.

COMMANDES
/dominion give caveblock
/dominion give finder_basic
/dominion give finder_advanced
/dominion give finder_elite

PERMISSIONS
dominion.admin (OP par défaut)
