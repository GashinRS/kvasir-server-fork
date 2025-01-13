# Changelog

All notable changes to this project will be documented in this file. See [conventional commits](https://www.conventionalcommits.org/) for commit guidelines.

---
## [0.2.0](https://gitlab.com/kvasir/kvasir-server/compare/0.1.10..0.2.0) - 2025-01-13

### Bug Fixes

- 409 on reboot when realm already exists, no longer crashes kvasir - ([7a53fc2](https://gitlab.com/kvasir/kvasir-server/commit/7a53fc27dce6343431b6aba1cca4fdc9b66fea77)) - tdupont

### Features

- Add basic health checks + monolith pod init readinessCheck - ([595e987](https://gitlab.com/kvasir/kvasir-server/commit/595e9879e1d91ef18a209292eaa19676e89f91d2)) - Jasper Vaneessen

### Miscellaneous Chores

- **(Compose)**: run Kvasir in host networking mode - ([ad55314](https://gitlab.com/kvasir/kvasir-server/commit/ad553147dcf0f610fef292be2375b8ce1dc88323)) - tdupont
- Docs updated and code cleanup - ([168cee0](https://gitlab.com/kvasir/kvasir-server/commit/168cee08356dde09a600a35fcb448d21d7febf2c)) - tdupont

### Tests

- **(Compose)** test profile and overview pages + overwrite image version - ([75cbdf6](https://gitlab.com/kvasir/kvasir-server/commit/75cbdf61ae81a98b4e50608b9f58f159bec09197)) - Jasper Vaneessen

### Build

- Prefer IPv4 - ([11f0588](https://gitlab.com/kvasir/kvasir-server/commit/11f058870f1cbf13b1b6fd45941158ac47f67c48)) - Jasper Vaneessen

### Ci

- Add smoke test for compose - ([f343f08](https://gitlab.com/kvasir/kvasir-server/commit/f343f08dafe8ab7b97823088aded7807b819b056)) - Jasper Vaneessen

---
## [0.1.10](https://gitlab.com/kvasir/kvasir-server/compare/0.1.9..0.1.10) - 2025-01-08

### Bug Fixes

- Set cors for dev - ([020ca33](https://gitlab.com/kvasir/kvasir-server/commit/020ca332d4814ea54a90c18814519b1c59744804)) - Thomas Dupont
- Allow suspicious types (CH) and use minio/minio image - ([e4eef5a](https://gitlab.com/kvasir/kvasir-server/commit/e4eef5a89c4230c6580ef1f8c4e43e9c3424d1ab)) - Jasper Vaneessen
- Added missing quarkus auth permission config - ([73ba618](https://gitlab.com/kvasir/kvasir-server/commit/73ba618d49298c3f06bb22959f91d2a7889596b3)) - Wannes Kerckhove
- Fixed some issues with Keycloak client init - ([0fcdadf](https://gitlab.com/kvasir/kvasir-server/commit/0fcdadf2232079d4737b92e3b9c39decb83cf1fe)) - Wannes Kerckhove
- Fixed KeycloakPolicyEnforcerAuthorizer should not be active in CDI when keycloak policy-enforcer is disabled - ([ef2f99d](https://gitlab.com/kvasir/kvasir-server/commit/ef2f99d666f45c3657db8c3834813be991f757fd)) - Wannes Kerckhove
- refer to new path of quarkus-realm.json - ([413a2ea](https://gitlab.com/kvasir/kvasir-server/commit/413a2eae8ed61391de6d135b6e7180d74511a73b)) - Thomas Dupont
- Fixed Slice creation failing (and updated wrong example in docs) - ([959a083](https://gitlab.com/kvasir/kvasir-server/commit/959a083393e64c335a50ec3bcec58ef0ba0f2ed0)) - Wannes Kerckhove
- Fixed disabling policy-enforcer not working properly. Policy-enforcer is now disabled by default when running tests - ([4089c75](https://gitlab.com/kvasir/kvasir-server/commit/4089c75d3406ce39e633d9c0db97e51f04a03543)) - Wannes Kerckhove
- removed hardcoded url references to keycloak and ui - ([70f14cd](https://gitlab.com/kvasir/kvasir-server/commit/70f14cdc1c89358d7add430a916350f1edfb4c71)) - Thomas Dupont
- added missing keycloak config to docker compose (deployment) - ([54ab1f5](https://gitlab.com/kvasir/kvasir-server/commit/54ab1f50602798553f03dd4d7766d4d4c3450529)) - Thomas Dupont

### Features

-  [**breaking**]Cursor-paging for the GraphQL API, expanded JSON-LD content negotiation for the GraphQL API - ([8e602b7](https://gitlab.com/kvasir/kvasir-server/commit/8e602b712187c7509a3ee164d1edc3d30967c9d8)) - Wannes Kerckhove
- setup per pod Keycloak realms automatically in dev mode - ([79bef46](https://gitlab.com/kvasir/kvasir-server/commit/79bef461cf908bc1088f60ecb90d09d1a7d036aa)) - Wannes Kerckhove
- proposal for public pod overview and public profile - ([d89a20f](https://gitlab.com/kvasir/kvasir-server/commit/d89a20fb2b5a62afb3917bbf5e6edf93a0a6c842)) - Wannes Kerckhove
- add kvasir-ui client generation to keycloak pod auth init - ([d5bce68](https://gitlab.com/kvasir/kvasir-server/commit/d5bce6816fc055fee9dc8bb0a6c07632410d4db2)) - tdupont
- default user accounts for demo pods - ([0f3ac6c](https://gitlab.com/kvasir/kvasir-server/commit/0f3ac6ce05009baa800399937cf624ec31eb52d5)) - Thomas Dupont

### Miscellaneous Chores

- **(dependencies)** update docker image dependencies (devservices+compose) and pin exact versions - ([0a8ce9c](https://gitlab.com/kvasir/kvasir-server/commit/0a8ce9c17deeb5e5b0d47f20cb2f92f67be4650d)) - Jasper Vaneessen
- **(renovate)** group CI and Docker categories - ([6d50315](https://gitlab.com/kvasir/kvasir-server/commit/6d50315438a6ef9c98f64a8b3e7cbf1b60d7760d)) - Jasper Vaneessen
- **(renovate)** update renovate config - ([643bc50](https://gitlab.com/kvasir/kvasir-server/commit/643bc503124ffc307b3fd0af954a5fd2fc6d57f9)) - Jasper Vaneessen
- add changelog - ([b7cc599](https://gitlab.com/kvasir/kvasir-server/commit/b7cc59985549266ff535a8c50ab34c3faca25aaf)) - Jasper Vaneessen
- master to main - ([f46ed3c](https://gitlab.com/kvasir/kvasir-server/commit/f46ed3c7729c77b4e1bb84c1762ec2b5b383dbf8)) - Jasper Vaneessen
- follow CH LTS version 24.8 - ([43f2c62](https://gitlab.com/kvasir/kvasir-server/commit/43f2c6272164d06813d319edcfd94bedb68fed79)) - Jasper Vaneessen
- move quarkus-realm.json to resources folder - ([131e04b](https://gitlab.com/kvasir/kvasir-server/commit/131e04bc4065f1fd21e1a770289354e472f9eb02)) - Thomas Dupont
- kvasir-ui 0.2.2 with keycloak auth in .deployment docker compose - ([5c0a34c](https://gitlab.com/kvasir/kvasir-server/commit/5c0a34c3e31487f558dd589df4e633f58c98fedd)) - Thomas Dupont

### Tests

- removed test scope dependency on clickhouse-plugin in storage-api (no longer needed because of build property disabling policy-enforcer) - ([bab321b](https://gitlab.com/kvasir/kvasir-server/commit/bab321bd292323bd28f5a52181f73676f10ac496)) - Wannes Kerckhove

### Build

- update maven and CI for testing and image deployment - ([4bebea8](https://gitlab.com/kvasir/kvasir-server/commit/4bebea851eebadc364afa2c23e2522c636bdccff)) - Jasper Vaneessen
- fixing missing CH dependency in storage-api - ([b7beb44](https://gitlab.com/kvasir/kvasir-server/commit/b7beb44420aef44c2afd806d224b8dbb4af7ae47)) - Wannes Kerckhove

---
## [0.1.9](https://gitlab.com/kvasir/kvasir-server/compare/0.1.8..0.1.9) - 2024-12-05

### Bug Fixes

- Added extra checks to prevent non-fully-qualified IRIs from being processed as valid change records - ([3de88fc](https://gitlab.com/kvasir/kvasir-server/commit/3de88fcd7687ca7b02065f94c04cd3b41925a7af)) - Wannes Kerckhove
- Fixed provided context not being used for querying (always used the pod's default context) - ([057ab86](https://gitlab.com/kvasir/kvasir-server/commit/057ab868f5743152401e7c301ef12af6d8344455)) - Wannes Kerckhove

### Features

- added support for filtering on named-graphs when querying using GraphQL (see `@graph` directive) - ([27422d0](https://gitlab.com/kvasir/kvasir-server/commit/27422d09978506a58d2dc99f12a7deffb0524d7c)) - Wannes Kerckhove
- Changes API now supports the JSON-LD '@reverse' keyword - ([d8942d7](https://gitlab.com/kvasir/kvasir-server/commit/d8942d7905981af18f374dd1018cece70e415269)) - Wannes Kerckhove

---
## [0.1.8](https://gitlab.com/kvasir/kvasir-server/compare/0.1.7..0.1.8) - 2024-12-03

### Bug Fixes

- Fixed issue with S3 proxy (caused by breaking change in Vert.x dependency) - ([ae16c0d](https://gitlab.com/kvasir/kvasir-server/commit/ae16c0dfc976aa8eb45316abefb2871c1bcb1079)) - Wannes Kerckhove
- Fixed runtime errors caused by build issues - ([875c9d7](https://gitlab.com/kvasir/kvasir-server/commit/875c9d705853af2b1adccc31fb569dc384765208)) - Wannes Kerckhove
- Fixed rdfs_Resource queries no longer giving results - ([80c03f7](https://gitlab.com/kvasir/kvasir-server/commit/80c03f7d5505ea106db9a1770093aa0794b39392)) - Wannes Kerckhove

### Ci

- dont build search index when key isn't set - ([32d451a](https://gitlab.com/kvasir/kvasir-server/commit/32d451aea2c3cdf44d634836b4498349d4830758)) - Jasper Vaneessen
- fix algolia key check - ([a80ee7b](https://gitlab.com/kvasir/kvasir-server/commit/a80ee7b14c639e76c85599eb75b76558622ab5f2)) - Jasper Vaneessen
- remove static set ALGOLIA_KEY (purely rely on CI/CD vars) - ([c3aba16](https://gitlab.com/kvasir/kvasir-server/commit/c3aba16f76ce544226190fdfd4dfb5bca7571e5c)) - Jasper Vaneessen

---
## [0.1.7](https://gitlab.com/kvasir/kvasir-server/compare/0.1.6..0.1.7) - 2024-11-28

### Bug Fixes

- Changes API now support JSON-LD named graphs - ([921c4c2](https://gitlab.com/kvasir/kvasir-server/commit/921c4c26ee9413a27d61a5058895c97179fd2247)) - Wannes Kerckhove
- Fixed issue with namespaces when executing GraphQL queries without a supplied context - ([17415d8](https://gitlab.com/kvasir/kvasir-server/commit/17415d8305806edff38572cdec39adc3861ec258)) - Wannes Kerckhove
- expose Link header through CORS for paging - ([9e2dcc5](https://gitlab.com/kvasir/kvasir-server/commit/9e2dcc5986be11b2fa07e2af606c9fba0218f5d8)) - Thomas Dupont
- change kvasir-ui port in dev docker compose to 3000 instead of 8081 - ([ae3f823](https://gitlab.com/kvasir/kvasir-server/commit/ae3f8230a0965d81a26dd67ec08c7b285b93e931)) - Thomas Dupont

### Documentation

- Updated getting started in docs to reflect repo README.MD - ([3fcb0dc](https://gitlab.com/kvasir/kvasir-server/commit/3fcb0dc58279edabe8ba9042b6c970f41bb2766d)) - Wannes Kerckhove
- added missing link to repo in getting started - ([b5c5a41](https://gitlab.com/kvasir/kvasir-server/commit/b5c5a41c0b3223262ecf526a36981a0154dd740d)) - Wannes Kerckhove
- Updated docs to reflect migration to Maven - ([313e653](https://gitlab.com/kvasir/kvasir-server/commit/313e653e6df72c8bf5c34ae4b8983ddd54c49c44)) - Wannes Kerckhove

### Features

- added first kvasir-ui image in docker compose - ([454fc7f](https://gitlab.com/kvasir/kvasir-server/commit/454fc7f89a48e1c93b98c507c08ae4ac35c03edb)) - Thomas Dupont
- introducing an @graph directive to specify which graph to query (no implementation yet) - ([940e795](https://gitlab.com/kvasir/kvasir-server/commit/940e795a8a1d9ae14261af8204fa2bcf1b8227eb)) - Wannes Kerckhove

### Miscellaneous Chores

- aadd healthcheck to demo compose - ([a1fca8f](https://gitlab.com/kvasir/kvasir-server/commit/a1fca8f49d26542912a14883c87e08cef1bc7fb8)) - Jasper Vaneessen
- cleanup - ([f74a9bf](https://gitlab.com/kvasir/kvasir-server/commit/f74a9bfdd787ba187185072b71ddcf771066fc1e)) - Wannes Kerckhove
- use port 8081 for the kvasir-ui - ([0518cf9](https://gitlab.com/kvasir/kvasir-server/commit/0518cf990ffe56536e102f8deec0dec6828eddfe)) - Thomas Dupont
- removed gradle wrapper and gradle build files - ([4450a16](https://gitlab.com/kvasir/kvasir-server/commit/4450a16ce60714e1abdbc6c51371bd2f6b9efad3)) - Wannes Kerckhove
- added maven wrapper - ([1ec1e67](https://gitlab.com/kvasir/kvasir-server/commit/1ec1e67c5fcec0088632d88f558d0653973d7be4)) - Wannes Kerckhove
- added Maven build files - ([d98d914](https://gitlab.com/kvasir/kvasir-server/commit/d98d914545c226de11fa6a237d08c1585665c893)) - Wannes Kerckhove
- updated .gitignore - ([a86b02d](https://gitlab.com/kvasir/kvasir-server/commit/a86b02da6e176469f17a5f83aeadd9b6853d92fc)) - Wannes Kerckhove

### Build

- monolith module can always include the quarkus-maven-plugin - ([1c60e9f](https://gitlab.com/kvasir/kvasir-server/commit/1c60e9f4807d9c5f7c12a96d27840eea392a6512)) - Wannes Kerckhove

### Ci

- change to mvn + mvnw executable - ([46088cb](https://gitlab.com/kvasir/kvasir-server/commit/46088cb2df812ded16e8c6a1849dc868cb59ff18)) - Jasper Vaneessen
- mvn cache - ([6f6fc65](https://gitlab.com/kvasir/kvasir-server/commit/6f6fc65994bb590014a62f77a469edd102ea7ff9)) - Jasper Vaneessen
- adapt workflow - ([a7a5ea8](https://gitlab.com/kvasir/kvasir-server/commit/a7a5ea88a554fa6cea1e7697285b053c11f7fc22)) - Jasper Vaneessen
- add needs to test:writerside - ([d5e99ff](https://gitlab.com/kvasir/kvasir-server/commit/d5e99ff1da55d3dd33b776724462386125ff9f61)) - Jasper Vaneessen

---
## [0.1.6](https://gitlab.com/kvasir/kvasir-server/compare/0.1.5-preview..0.1.6) - 2024-11-25

### Bug Fixes

- possible fix for S3 delete hanging - ([58fe8a8](https://gitlab.com/kvasir/kvasir-server/commit/58fe8a89a31c93d8f25f7b77bb7a6a18ae6cd26a)) - Wannes Kerckhove

---
## [0.1.5-preview](https://gitlab.com/kvasir/kvasir-server/compare/0.1.4-preview..0.1.5-preview) - 2024-11-25

### Bug Fixes

- CORS handler should also allow all when not running in dev-mode (by default) - ([e2f1689](https://gitlab.com/kvasir/kvasir-server/commit/e2f1689239f1812e6bb3065fe27fc9fc9179eeb4)) - Wannes Kerckhove

---
## [0.1.4-preview](https://gitlab.com/kvasir/kvasir-server/compare/0.1.3-preview..0.1.4-preview) - 2024-11-25

### Bug Fixes

- Fixed change request trigger upon S3 deletion of an RDF file (also introduced Bucket versioning) - ([149e1f7](https://gitlab.com/kvasir/kvasir-server/commit/149e1f70b944798e474a689d9a9f77b177c8517d)) - Wannes Kerckhove
- Fixed error while viewing swdemo change records (due to unsupported language tag) - ([bd674d1](https://gitlab.com/kvasir/kvasir-server/commit/bd674d1a101b2f948d247ba8d0a80fba28e5a49d)) - Wannes Kerckhove
- FIxed pagination in GraphQL for nested fields - ([3a1cec4](https://gitlab.com/kvasir/kvasir-server/commit/3a1cec4a263c8a9c7c29487c21cf319a9b5a8d6b)) - Wannes Kerckhove
- Fixed some issues with Slices - ([d0f7e3a](https://gitlab.com/kvasir/kvasir-server/commit/d0f7e3add9c19751c38324a754423188fc7fe5e6)) - Wannes Kerckhove
- Fixed current pod config properties not showing via pod management API - ([661645c](https://gitlab.com/kvasir/kvasir-server/commit/661645ce3cd15fac982a1ad5543b21c486c5fd37)) - Wannes Kerckhove
- Fixed pod management pod config update - ([e1e1ced](https://gitlab.com/kvasir/kvasir-server/commit/e1e1ced3e88f12455a20b556387c453c39c88fff)) - Wannes Kerckhove

### Features

- implemented pagination for change history (internal implementation is offset based and should be improved in the future) - ([64d8f41](https://gitlab.com/kvasir/kvasir-server/commit/64d8f4118c22560e530a181df6efe271f29f8c16)) - Wannes Kerckhove
- implemented basic offset based paging for GraphQL querying (cfr. Stardog or Ruben T implementations) - ([83021fb](https://gitlab.com/kvasir/kvasir-server/commit/83021fbeff940edfb7a276025dc495c533ae944e)) - Wannes Kerckhove
- totalCount is now available for non-scalar relationships - ([8000fbf](https://gitlab.com/kvasir/kvasir-server/commit/8000fbfd4384eec60d52bb91e863170b5b6d86cd)) - Wannes Kerckhove

### Wip

- working on totalCount implementation - ([f5a509a](https://gitlab.com/kvasir/kvasir-server/commit/f5a509a88ff7840d1f4ea2d0580be753dcfcaa67)) - Wannes Kerckhove

---
## [0.1.3-preview](https://gitlab.com/kvasir/kvasir-server/compare/0.1.2-preview..0.1.3-preview) - 2024-11-20

### Bug Fixes

- prevent the creation of a Slice if the name already exists - ([8319ca1](https://gitlab.com/kvasir/kvasir-server/commit/8319ca1d8fc72c5f9dcaaac5a45361d1bf667a95)) - Wannes Kerckhove

### Documentation

- Updated README.MD (to include a section on running with docker compose) - ([0bcbe54](https://gitlab.com/kvasir/kvasir-server/commit/0bcbe547c9e543edea1fa540f89b39a78207fa70)) - Wannes Kerckhove
- Updated readme - ([8cb229d](https://gitlab.com/kvasir/kvasir-server/commit/8cb229d6cd6dd813c16505ab8849d3361ee0b08e)) - Wannes Kerckhove
- updated docs (documented request language-tag when querying) - ([a2bb8c2](https://gitlab.com/kvasir/kvasir-server/commit/a2bb8c24bd0dc114ba10d198553ab4ead6148a08)) - Wannes Kerckhove

### Features

- Query engine now support context language tag - ([58a59f0](https://gitlab.com/kvasir/kvasir-server/commit/58a59f063ac368cdb4f60dcde96f40ef9cbe83c0)) - Wannes Kerckhove

### Miscellaneous Chores

- removing old xtdb startup scripts - ([272b286](https://gitlab.com/kvasir/kvasir-server/commit/272b286e9f4d511c51ae908a454f7214db6c801c)) - Wannes Kerckhove
- removed legacy xtdb code in kg implementation module - ([025f330](https://gitlab.com/kvasir/kvasir-server/commit/025f33007590e4194ca4fa46bbc061a86e716760)) - Wannes Kerckhove

### Ci

- added a docker-compose deployment folder - ([171f4e8](https://gitlab.com/kvasir/kvasir-server/commit/171f4e8dadb262c297ff0fa5ae487d4a9d824d74)) - Wannes Kerckhove

---
## [0.1.2-preview](https://gitlab.com/kvasir/kvasir-server/compare/0.1.1-preview..0.1.2-preview) - 2024-11-19

### Ci

- Quick fix for docker rate limit - ([9e235fe](https://gitlab.com/kvasir/kvasir-server/commit/9e235fec5962893962ce8ad2e45c7bb923134e0c)) - Wannes Kerckhove

---
## [0.1.1-preview](https://gitlab.com/kvasir/kvasir-server/compare/0.1.0-preview..0.1.1-preview) - 2024-11-19

### Bug Fixes

- fixed prefix name for Clickhouse config - ([421ccf8](https://gitlab.com/kvasir/kvasir-server/commit/421ccf8e51c4b2bd81b2a02cb36f75af858d8521)) - Wannes Kerckhove

---
## [0.1.0-preview] - 2024-11-19

### Bug Fixes

- **(doc)** algolia vars wrongly quoted - ([16a3733](https://gitlab.com/kvasir/kvasir-server/commit/16a3733d44199bf345604fccf1c809fd775af219)) - tdupont
- **(doc)** set style explicilty on images in paragraph - ([34c609e](https://gitlab.com/kvasir/kvasir-server/commit/34c609ed7f0749110796232c0b4043d6fb429ab4)) - tdupont
- Fixed #1 - ([26caa9f](https://gitlab.com/kvasir/kvasir-server/commit/26caa9f4d481adcfe67fa65d3bc96c889963e3ba)) - Wannes Kerckhove
- filtering on value using field argument now works for 'id' fields as well. - ([7a94fbe](https://gitlab.com/kvasir/kvasir-server/commit/7a94fbe2709bada7653d0da5c324327cd1b86e26)) - Wannes Kerckhove
- Fixed nested GraphQL queries when fields contain upercase characters - ([8a0200e](https://gitlab.com/kvasir/kvasir-server/commit/8a0200e24550ecefc5cc2661766faecec9472c86)) - Wannes Kerckhove
- Fixed insert/delete templates using where for ChangeRequest + feat: Additional delete options - ([75ce301](https://gitlab.com/kvasir/kvasir-server/commit/75ce301abe79457d7f5d0711851afeb780c1ddf5)) - Wannes Kerckhove
- .gitlab-ci should be at the root level - ([325b5b2](https://gitlab.com/kvasir/kvasir-server/commit/325b5b29207d59cb1c8a3e67387d868a6c70fd7b)) - tdupont
- cors header removed form minio response (quarkus already does cors) - ([fa08b27](https://gitlab.com/kvasir/kvasir-server/commit/fa08b27f0cad2a19f56be03aa7f0e2fe315c0f49)) - tdupont
- decode path and query before aws signing - ([f0e67dd](https://gitlab.com/kvasir/kvasir-server/commit/f0e67dd40b625854c320c1b9c85fbe9fa239503a)) - Thomas Dupont
- Fixed wrong object id being passed on via Kafka - ([93b87b0](https://gitlab.com/kvasir/kvasir-server/commit/93b87b0c80ffe52d3f91b5225f5064c9f1497c1d)) - Wannes Kerckhove
- on empty query, default to empty string for aws signing url - ([4749bdf](https://gitlab.com/kvasir/kvasir-server/commit/4749bdf0dce2412da98b7f69613a5505d6f4919f)) - tdupont
- on empty query, default to empty string for aws signing url - ([bf488ab](https://gitlab.com/kvasir/kvasir-server/commit/bf488ababb49e92cf6efa76d46d3619467d0ef33)) - tdupont
- graphql empty errors array should be undefined - ([db91600](https://gitlab.com/kvasir/kvasir-server/commit/db916001125c553f1b0ba69795e06cce6b1222e6)) - Thomas Dupont
- Fixed getting id for external resource - ([3476f46](https://gitlab.com/kvasir/kvasir-server/commit/3476f46f3dbdd70e49bb029629766ca0d57aea75)) - Wannes Kerckhove
- Fixed id filter - ([4e2cea2](https://gitlab.com/kvasir/kvasir-server/commit/4e2cea2251f055d676bc814b74e65d05660c3701)) - Wannes Kerckhove
- Fix for predicate target data loading exceeding Clickhouse field value size (limited Dataloader batching) - ([ae899d5](https://gitlab.com/kvasir/kvasir-server/commit/ae899d548fc07b30d70b7fda443cd888ecfc8f2d)) - Wannes Kerckhove
- [GraphQL] Fixed issues when no variables are used - ([68f6de2](https://gitlab.com/kvasir/kvasir-server/commit/68f6de28e043d0d9f12139d9aadeef248e573252)) - Wannes Kerckhove
- Streaming endpoint for changes should just be /changes with a different Accept (text/event-stream) header - ([bfd0c7e](https://gitlab.com/kvasir/kvasir-server/commit/bfd0c7ee391202b2a881f45bf03c4cbe60dca604)) - Wannes Kerckhove

### Documentation

- Added basic readme with usage examples - ([2a08907](https://gitlab.com/kvasir/kvasir-server/commit/2a08907c01beca4e0cb6bc7d72dc4c01ad6954c6)) - Wannes Kerckhove
- small changes to readme - ([cd269b3](https://gitlab.com/kvasir/kvasir-server/commit/cd269b38d6c7eba24c0fec429c10322c71815a88)) - Wannes Kerckhove
- Added link to Xtdb - ([662e944](https://gitlab.com/kvasir/kvasir-server/commit/662e94429a72497067e9a40b628bf111564acf52)) - Wannes Kerckhove
- Include docs for latest features - ([bcb2f17](https://gitlab.com/kvasir/kvasir-server/commit/bcb2f17d491b755a0b9615f255e1bd21360a0778)) - Wannes Kerckhove
- Updated openapi/swagger documentation - ([b7d1e7a](https://gitlab.com/kvasir/kvasir-server/commit/b7d1e7ab95a71b857fd53cffaa92f50ee0bba78b)) - Wannes Kerckhove
- Working on setting up Writerside docs - ([1c89344](https://gitlab.com/kvasir/kvasir-server/commit/1c89344d969247078dfdd3e94d44cb9395071978)) - Wannes Kerckhove
- provided basic documentation - ([66009c8](https://gitlab.com/kvasir/kvasir-server/commit/66009c8883ecd3c993f53c7b7b303265c1dcdd47)) - Wannes Kerckhove
- small fixes - ([f664f84](https://gitlab.com/kvasir/kvasir-server/commit/f664f844ee524ac80ef522c505504d3df062ca23)) - Wannes Kerckhove
- small tweaks - ([d1b2edd](https://gitlab.com/kvasir/kvasir-server/commit/d1b2edd1c5e17684bb129f352df819518ca08549)) - tdupont
- algolia search added - ([267003a](https://gitlab.com/kvasir/kvasir-server/commit/267003adcc9a9213c560c69c1fa60bad370951ca)) - tdupont
- updated docs to reflect Query API changes - ([3ef9b8a](https://gitlab.com/kvasir/kvasir-server/commit/3ef9b8a7e8d2d09416dc2ccf07b47d067dcc709b)) - Wannes Kerckhove
- updated Inbox docs to reflect changes to Querying - ([710cf22](https://gitlab.com/kvasir/kvasir-server/commit/710cf22d2b541a18f1ee91436ac4942d802cd03f)) - Wannes Kerckhove
- Added some content to architecture + a motivation for using GraphQL. - ([c958d5f](https://gitlab.com/kvasir/kvasir-server/commit/c958d5f6a5439852933fed2686bc736044b0e020)) - Wannes Kerckhove
- Added stream flow diagram - ([21ad494](https://gitlab.com/kvasir/kvasir-server/commit/21ad494d87561dfc2b00e3634ee3d879bc68dbbe)) - Wannes Kerckhove
- updating docs - ([07d5ef3](https://gitlab.com/kvasir/kvasir-server/commit/07d5ef3a0db84a4e45b8d2b4c03fd2a74bcc1a9d)) - Wannes Kerckhove
- updating docs - ([b284ed4](https://gitlab.com/kvasir/kvasir-server/commit/b284ed40b7aab975d4526659b8147c8456bcf5fc)) - Wannes Kerckhove

### Features

- added additional query features while fixing some bugs - ([96f6eac](https://gitlab.com/kvasir/kvasir-server/commit/96f6eac8c47265dd100a46f8867ea0b90457ec14)) - Wannes Kerckhove
- Implemented S3 low-level storage API - ([84b1267](https://gitlab.com/kvasir/kvasir-server/commit/84b126796e1ac1c24c6b94338af324d0c2cd7114)) - Wannes Kerckhove
- GraphQL queries now support namespaces prefixes (using underscore as separator) - ([6d64ba9](https://gitlab.com/kvasir/kvasir-server/commit/6d64ba9971fb8e8f699151f9f91e43d21e46d2c9)) - Wannes Kerckhove
- GraphQL query API updates with introspection field '__fieldnames' to list possible predicate IRIs for a specific selection. - ([6be4aac](https://gitlab.com/kvasir/kvasir-server/commit/6be4aac5033e70239795d766432c607e50ee2ae2)) - Wannes Kerckhove
- added support for additional selection criteria as GraphQL field arguments - ([947e873](https://gitlab.com/kvasir/kvasir-server/commit/947e873c623d4de2d75c4046761a1ef79418217b)) - Wannes Kerckhove
- specify target graph when performing inbox or query requests + query endpoint can now also return JSON-LD directly (based on context supplied in request) - ([7819ffe](https://gitlab.com/kvasir/kvasir-server/commit/7819ffe0e1a17b4a3dfc25c44101161afaf47af7)) - Wannes Kerckhove
- ChangeRequests (inbox API) now support assertions and GraphQL based insert/delete templates - ([b9456a5](https://gitlab.com/kvasir/kvasir-server/commit/b9456a5b1474d3371883c4fb95fde597f95ea0a6)) - Wannes Kerckhove
- update via delete/insert with bindings ChangeRequest is now available - ([2f6e13f](https://gitlab.com/kvasir/kvasir-server/commit/2f6e13f5dc6fae117405824b61b4fcd1786be25c)) - Wannes Kerckhove
- Implemented special GraphQL field totalCount - ([786b84e](https://gitlab.com/kvasir/kvasir-server/commit/786b84e96a3cfe8d54d65de0fe329daeeb63df65)) - Wannes Kerckhove
- added kafka channel initializers so explicit cdi imports are not required in each module - ([c3b6223](https://gitlab.com/kvasir/kvasir-server/commit/c3b6223696fa2e5a0037e7569cfa8949c3f5d859)) - Wannes Kerckhove
- started working on slices (subgraphs) - ([f75ca7f](https://gitlab.com/kvasir/kvasir-server/commit/f75ca7f3e46b5fa8102e639042f606b25eb2f420)) - Wannes Kerckhove
- updated GraphQLToSQL conversion to support top-level type entry-points - ([bdf2394](https://gitlab.com/kvasir/kvasir-server/commit/bdf239474009616b82d55c5a5f1824a131ce72f0)) - Wannes Kerckhove
- Support for basic GraphQL introspection and fixed some issues with updated QL - ([e5c07a5](https://gitlab.com/kvasir/kvasir-server/commit/e5c07a5c7d9cc8a4ac9ad5afd9fd132e8c59a2e6)) - Wannes Kerckhove
- QL support for array arguments, having IN semantics - ([0cbcc9a](https://gitlab.com/kvasir/kvasir-server/commit/0cbcc9a6d13ea6309d7e3fb8bb97b27ff0b95d4b)) - Wannes Kerckhove
- implemented RSQL expression based @filter directives - ([6a89f26](https://gitlab.com/kvasir/kvasir-server/commit/6a89f26222e3825fc4374c5fa88b926e07c2e204)) - Wannes Kerckhove
- Introspection works. KG can be navigated an interacted with using GraphiQL - ([1978b07](https://gitlab.com/kvasir/kvasir-server/commit/1978b07baf22cc592b9cd0ea1231d1d758d380eb)) - Wannes Kerckhove
- default prefix mapping is now part of pod config (instead of fetching a fixed standard mapping file). - ([11201bf](https://gitlab.com/kvasir/kvasir-server/commit/11201bf1d4fc1b91307f1f5a951b9e48130a364a)) - Wannes Kerckhove
- made Amazon content signature optional for s3 storage requests, which makes the API easier to use. - ([b6b4223](https://gitlab.com/kvasir/kvasir-server/commit/b6b42237c7858bd955ead83fc09bdb8aa88029ba)) - Wannes Kerckhove
- ChangeRequests can now contain references to internal S3 storage with objects to delete/insert - ([91cd8a7](https://gitlab.com/kvasir/kvasir-server/commit/91cd8a79002f86ab58713a77e7741d79195be984)) - Wannes Kerckhove
- new query engine based on a preconstructed schema - ([01637a3](https://gitlab.com/kvasir/kvasir-server/commit/01637a362990522e5bcede002699690cd8125735)) - Wannes Kerckhove
- Implemented Slice store for Clickhouse - ([9657aea](https://gitlab.com/kvasir/kvasir-server/commit/9657aeada69602993947e46a868f7c5f57c75146)) - Wannes Kerckhove
- initial poc implementation of Slice API - ([3297f1a](https://gitlab.com/kvasir/kvasir-server/commit/3297f1a0b16ed7907fdaef9713956924bff38962)) - Wannes Kerckhove
- kvasir-ui now in docker compose file (port 8081) - ([a104ba4](https://gitlab.com/kvasir/kvasir-server/commit/a104ba479069e0a831ddaf1e804271ba64a91be3)) - tdupont
- implemented an HTTP body interceptor, allowing a more generic approach to how we handle JSON-LD request/response bodies. - ([99d7822](https://gitlab.com/kvasir/kvasir-server/commit/99d782287b27f1cc1eead3b849482c64fed93429)) - Wannes Kerckhove
- added support for GraphQL variables - ([097ce56](https://gitlab.com/kvasir/kvasir-server/commit/097ce56962cbe22736322b53b92f53e65a5658ef)) - Wannes Kerckhove
- added notifications for Slice changes - ([fd2bf1b](https://gitlab.com/kvasir/kvasir-server/commit/fd2bf1bd72645ca8360f53e33f2b55859aab1fde)) - Wannes Kerckhove
- added notifications for Slice changes - ([556c5cf](https://gitlab.com/kvasir/kvasir-server/commit/556c5cfcfb80d5cbea10243fb33ffb3087335090)) - Wannes Kerckhove
- added improved implementation of the CH data fetcher - ([19cb8b1](https://gitlab.com/kvasir/kvasir-server/commit/19cb8b192cc83e36a15b39932d57af368ea81d2b)) - Wannes Kerckhove
- pod management API - ([56470cc](https://gitlab.com/kvasir/kvasir-server/commit/56470cc27d9f22ae85d17f22f22f600cba6dcf54)) - Wannes Kerckhove
- Implemented Query API time travel - ([8b1498a](https://gitlab.com/kvasir/kvasir-server/commit/8b1498aaf94f6f55f89fe6c33dd29c5f42d36c49)) - Wannes Kerckhove

### Miscellaneous Chores

- removed clickhouse-kg module as a CH implementation is not needed atm - ([b2bdee9](https://gitlab.com/kvasir/kvasir-server/commit/b2bdee996bba8865432d19111e5ec9c2bf974f78)) - Wannes Kerckhove
- added license - ([a0b7d54](https://gitlab.com/kvasir/kvasir-server/commit/a0b7d5439f55334c912f05d3fafb74a70ca6fd21)) - Wannes Kerckhove

### Refactoring

- renamed ChangeRequest where field to with - ([303431a](https://gitlab.com/kvasir/kvasir-server/commit/303431a92838d1d233a327db76eeda979c6cadcd)) - Wannes Kerckhove

### Tests

- added storage api (s3 proxy) basic test - ([082de4d](https://gitlab.com/kvasir/kvasir-server/commit/082de4d0d046ec4d9ed405b03738ab9e45a2154a)) - Wannes Kerckhove
- Wrote some basic tests for Xtdb KG mutations - ([155323a](https://gitlab.com/kvasir/kvasir-server/commit/155323aaca01ad6db24628d42c3e501cf7edfccb)) - Wannes Kerckhove

### Busy

- working on class-based query entry-points - ([3192131](https://gitlab.com/kvasir/kvasir-server/commit/3192131327ae0dcb919eef1d3836e78323e387aa)) - Wannes Kerckhove

### Ci

- publish Writerside pages - ([a5085d2](https://gitlab.com/kvasir/kvasir-server/commit/a5085d27d5fbcbb69f8a9cda4a1af4d410b8fff3)) - tdupont
- basic gradle pipeline (no testing, dummy deployment) - ([cd1a329](https://gitlab.com/kvasir/kvasir-server/commit/cd1a329add486de6cd58ef71306f727d585df836)) - Jasper Vaneessen
- basic gradle pipeline (no testing, dummy deployment) - ([1986833](https://gitlab.com/kvasir/kvasir-server/commit/1986833fb05ac333c887e6a37dc19ec5ec628807)) - Jasper Vaneessen
- updating image version for writerside build - ([70c63c3](https://gitlab.com/kvasir/kvasir-server/commit/70c63c34c7db592541150f1849a082696550fbc1)) - Wannes Kerckhove
- build kvasir ui only from latest main branch - ([c01ac94](https://gitlab.com/kvasir/kvasir-server/commit/c01ac94caa9084d4e402fab652ace0de7405c931)) - tdupont

### Eval

- KG metadata is now constructed at ingest time. Experimenting with schema-first query resolving. - ([db1ee8d](https://gitlab.com/kvasir/kvasir-server/commit/db1ee8d35a8df1a334c60d76052bd90b234aab5e)) - Wannes Kerckhove

### Git

- updated .gitignore to exclude kotlin compiler tmp files - ([4104945](https://gitlab.com/kvasir/kvasir-server/commit/410494577aecb5bb8915b128784a678eae731028)) - Wannes Kerckhove

### Overhaul

- abstracted part of the query engine and replaced xtdb with Clickhouse as default implementation. - ([1e01181](https://gitlab.com/kvasir/kvasir-server/commit/1e0118183eaa2620b93ed492e1b4153eb78d7ac2)) - Wannes Kerckhove

### Wip

- graphql to sql shaping up - ([3ac9629](https://gitlab.com/kvasir/kvasir-server/commit/3ac962987673184fb1355a04c70e6a9660578551)) - Wannes Kerckhove
- alternative change commands + trying to fix query bug - ([ece5d2f](https://gitlab.com/kvasir/kvasir-server/commit/ece5d2fd1e8c9882fb9362fc86dae111c2bcec81)) - Wannes Kerckhove
- preparing history api - ([e5f73e9](https://gitlab.com/kvasir/kvasir-server/commit/e5f73e9c47eb529621a1750d71ebc9ad21a8ed79)) - Wannes Kerckhove
- working on change history + complete API structure overhaul - ([0a95840](https://gitlab.com/kvasir/kvasir-server/commit/0a95840eef1a2187fc1284bd868d986fb91708b9)) - Wannes Kerckhove

<!-- generated by git-cliff -->
