# Décisions techniques

## 1. Architecture

Le produit reste un monolithe Spring Boot avec un index en mémoire. Pour un micro-produit défendable en 45 minutes, cela réduit le nombre de composants opérationnels et rend le chemin question -> retrieval -> réponse lisible. Le corpus de démonstration est embarqué dans les resources ; les documents ajoutés par l'API sont persistés dans `data/runtime/documents.json`.

**Compromis :** l'index est perdu à l'arrêt pour les documents non persistés et n'est pas adapté à un corpus volumineux ou multi-instance. Une prochaine version utiliserait PostgreSQL/pgvector ou une base vectorielle managée, avec versionnement d'index et migrations.

## 2. Embeddings et génération

Le provider `local` utilise un embedding lexical/hashé déterministe et une génération extractive. Cette stratégie est volontairement transparente, rapide et sans appel réseau : elle permet de reproduire l'évaluation et de rester factuel dans un environnement de live demo. Le provider `ollama` implémente le même contrat pour brancher des embeddings et un chat model local.

**Compromis :** le provider local n'a pas la compréhension sémantique d'un modèle neuronal et les métriques de la démo ne préjugent pas de la qualité Ollama. Avant une mise en production : baseline séparée par provider, tests multilingues, questions paraphrasées et double annotation humaine.

## 3. Chunking et retrieval

Les paragraphes sont conservés quand ils sont courts ; les longs paragraphes sont découpés en fenêtres de 95 mots avec 18 mots de recouvrement. Le filtre de rôle est appliqué **avant** le classement et le seuil relatif/absolu est appliqué avant génération. Ainsi, un fragment restreint ne peut pas être récupéré par un collaborateur, même s'il est très pertinent.

**Limites :** pas de reranker, pas de recherche hybride BM25/vectorielle et pas de déduplication avancée. Les prochaines étapes sont un index persistant, un reranking cross-encoder et une évaluation de recall@k sur un corpus plus large.

## 4. Garde-fous et politique de données

Les entrées sont validées par Jakarta Validation, bornées en taille, inspectées par une politique anti-injection/exfiltration et classifiées `PUBLIC`, `INTERNAL` ou `RESTRICTED`. Les rôles sont délibérément simples : invité (public), collaborateur (public + interne), administrateur (tout). Une question non couverte reçoit `insufficient_context` plutôt qu'une hallucination.

Les traces sont JSONL, synchronisées, et passent par une redaction des emails, téléphones, IBAN et motifs de secrets. Les réponses affichent leurs citations et un `traceId`, tandis que les versions du pipeline sont conservées pour l'audit.

**Limites :** les regex ne remplacent ni un DLP complet ni une identité fédérée. En production, il faudrait OAuth2/OIDC, autorisations par tenant/document, chiffrement au repos, rotation des clés, rétention configurable et revue des faux positifs/faux négatifs.

## 5. Evaluation Gen-e2 / AI-First

Le golden set est versionné avec le code et couvre des cas positifs, inconnus, adversariaux et RBAC. Le quality gate combine :

- retrieval hit rate : 30 % ;
- couverture de mots-clés factuels : 25 % ;
- exactitude des garde-fous : 20 % ;
- intégrité des citations : 15 % ;
- contrôle d'accès : 10 %.

Cette boucle correspond à une approche Gen-e2 : expliciter les objectifs, générer avec un contexte borné, observer chaque étape, puis évaluer automatiquement avant le debrief. Les mots-clés sont un proxy explicable et peu coûteux ; ils ne remplacent pas un jugement humain calibré.

## 6. Gen-e2 : contexte, feedback loops et AI-PDLC

L'adaptation ne consiste pas seulement à utiliser un LLM pour écrire du code. Le dépôt conserve le contexte utile au même endroit : objectif produit et parcours de démonstration dans le README, compromis dans ce fichier, contrats API dans le code, corpus et golden set versionnés, et traces/résultats comme preuves d'exécution. Les prompts courts peuvent donc s'appuyer sur cet état partagé plutôt que reconstruire le contexte à chaque étape.

Chaque boucle suit le cycle **intention -> génération -> vérification -> contexte enrichi** : l'intention métier devient un contrat observable, le pipeline génère avec un contexte borné et un provider explicite, les traces et l'évaluation vérifient qualité/sécurité/citations/latence, puis les décisions et preuves sont conservées dans le dépôt pour la boucle suivante.

La séparation Angular/Spring couvre toute la chaîne : l'interface rend visibles provider, score, timings et `traceId`, tandis que le backend garde les politiques, l'évaluation et l'audit. Le workflow est ainsi un AI-PDLC compact : contexte partagé, génération par contrats, quality gate automatisé et debrief fondé sur les traces.

## 7. Déploiement et observabilité

Les headers de sécurité sont ajoutés par filtre Servlet et les routes sensibles exigent un rôle admin ainsi qu'une clé optionnelle. L'Actuator expose uniquement `health`, `info` et `metrics`.

**Next steps prioritaires :** authentification réelle, métriques Prometheus, corrélation logs/metrics/traces, quotas par utilisateur, CI avec quality gate, puis canary du provider Ollama.
