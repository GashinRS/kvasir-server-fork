# Changelog

All notable changes to this project will be documented in this file. See [conventional commits](https://www.conventionalcommits.org/) for commit guidelines.

---

## [0.16.1](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/v0.16.0..v0.16.1) - 2026-01-30
### Bug Fixes
- configproperty classloading issue - ([b663f3e](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/b663f3eaf88cdd32eca24287b7017debc476ebf3)) - Thomas Dupont
### Other
- Updated documentation to reflect changes in UMA integration - Thomas Dupont

---

## [0.16.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/v0.15.0..v0.16.0) - 2026-01-29
### Bug Fixes
- **(ui)** upon posting a CR the page is no longer blank - ([f373bf4](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/f373bf494fb19fb07526fb2087e916fafc658560)) - Thomas Dupont
- Fixed issue with timestamp serializing to JSON-LD - ([b0f8ade](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/b0f8adee074bab764adb6ab909ed5de4bc818b04)) - Wannes Kerckhove
### Features
- uma integration - ([5a64aca](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/5a64acacc142a47c02e6de7830f3b22edfdd1c9f)) - Thomas Dupont

---

## [0.15.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/v0.14.0..v0.15.0) - 2026-01-23
### Bug Fixes
- **(ui)** extra guard against platform config still being fetched - ([d7e3f4c](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/d7e3f4cf7dbd5cd3188a572cecaf18c66836be8a)) - Thomas Dupont
-  [**breaking**]Updated generic POJO ORM implementation (fixing some conceptual issues) - ([0068d21](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/0068d21506baa0fdf86702729e895b6568332ddf)) - Wannes Kerckhove
- podconfig overrides functionality - ([7174a20](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/7174a2083e92979f600dc0e4eeee1f68354931eb)) - Thomas Dupont
### Features
- **(deps)** Update Kotlin to 2.0.21 and manage with BOM - ([bf5e793](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/bf5e793e3324f620d166249fdd59c8b1e2ae8c62)) - Thomas Dupont
- **(ui)** settings overhaul - ([a4afb24](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a4afb24498278c9134629fcd1d295afc851a5801)) - Thomas Dupont
- When registering a pod via the API, an optional admin client credential pair can now be set (for quick bootstrapping) - ([31f7d423](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/-/commit/31f7d4233b4a24ebf63b9fdb10d76219b3225d9f)) - Wannes Kerckhove

---

## [0.14.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/v0.13.0..v0.14.0) - 2025-12-08
### Notable changes
1. Auth system overhaul - ([4ca4f6c](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/4ca4f6c9a05344424222b43d47f6e16999082f3f)) - Wannes
  - New auth framework allows multiple policy mechanism to be active within the same runtime.
  - Supported policy mechanisms are:
    - OpenFGA
    - A4DS/UMA
    - External HTTP endpoint PEP
  - Added support for DPoP (can be configured to be required for additional security)
  - Added support for Solid-OIDC (authenticating via a WebID)
2. New Pod configuration system  - ([4ca4f6c](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/4ca4f6c9a05344424222b43d47f6e16999082f3f)) - Wannes
  - Updated model provides clear distinction between default system Pod settings, Pod bootstrap settings and User-defined Pod settings
  - User-defined Pod settings (`kss:configuration`) are now represented as a string that follows the exact same structure as the Kvasir config files (vs. having a separate RDF-based model).
  - User-defined Pod settings are overlayed on top of the system settings using the [SmallRye Config system](https://smallrye.io/smallrye-config/Latest/) (vs. the custom built solution before)
3. Kvasir UI updated to reflect backend changes - ([5c306e0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/5c306e0269ae6dca63208286729c9faef6802a12)) - Thomas Dupont
  - When a Keycloak user is available with owner-level-access to the pod (configured in OpenFGA), you can login and use the Kvasir UI, even when UMA is enabled for the Pod.
  - Improved access control UI
  - Added forms for managing the Pod configuration.

---

## [0.13.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/v0.12.0..v0.13.0) - 2025-10-28
### Features
- Add Solid compliant storage API - ([855932c](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/855932c497f5c4e34cf046ddb84abe2272dbbe2f)) - Wannes Kerckhove
- **(ui)** Enforces UI logout on Keycloak state error - ([a522ef3](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a522ef3b01a69a59a03dc7545c13834aa1b5a4ba)) - Thomas Dupont
### Bug Fixes
- Support reverse relations via predicate directive for GraphQL mutations - ([bc5c11a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/bc5c11a975003c53e2cb1886b161054be0b392f3)) - Wannes Kerckhove

---

## [0.12.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/v0.11.1..v0.12.0) - 2025-10-10
### Bug Fixes
- Blank nodes are now skolemized for change requests - ([5838c3a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/5838c3af10205932323c581325d552cac3cb1246)) - Wannes Kerckhove
- Workaround for reading body in HttpAuthenticationMechanism - ([a827b23](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a827b2370638ecea13cbbf967727c484b3c6638e)) - Wannes Kerckhove
- Resource IDs should not be included in results after removal of all its properties - ([cbc82da](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/cbc82daa6f8586d96f8321ae8aed2564765575d4)) - Wannes Kerckhove
### Features
- **(ui)** Support for result code history in change request details - ([4d94cc4](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/4d94cc4f5acc9387dcc5d75a18cc017c6a562136)) - Thomas Dupont
- Expose the policy.agent property to the ui - ([447c426](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/447c426571a1a8391046ccec3b290506bd27a06c)) - Thomas Dupont

---

## [0.11.1](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/v0.11.0..v0.11.1) - 2025-09-22
### Bug Fixes
- missing builds due to CI misconfig - ([921b5de](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/921b5de25ee2f57ddd83537d9efa2ad6dbc72d06)) - Jasper Vaneessen

---

## [0.11.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/v0.10.0..v0.11.0) - 2025-09-22
### Bug Fixes
- Introducing proper isolation between the policy modes (via classpath and maven profiles) - ([d0e03c4](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/d0e03c4786c2902f34533e8b773d79d44396ee3a)) - Wannes Kerckhove
### Features
- preview SDL schema functionality - ([69d2d1b](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/69d2d1bedaf0d668b8d78f07b8ddd7d1aa490931)) - Thomas Dupont
- init-service can now be configured to terminate after completion via kvasir.bootstrap.exit-after-setup=true - ([2250a49](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/2250a496e05c31211a00a063da95c1459c42bf6d)) - Jasper Vaneessen

---

## [0.10.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/v0.9.2..v0.10.0) - 2025-09-15
**Warning**: This update modifies the storage schema for Clickhouse. At this time, we cannot provide a migration script, meaning you will have to manually reset the database (e.g. by clearing storage volumes) before using this update!
### Features
- Extended the GraphQL typesystem with support for Date, Time & DateTime - ([a451bbc](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a451bbc6a3a7b63b8d0d2b3a86e2cdaa89d525a8)) - Wannes Kerckhove
- Implemented (basic) support for Authorization for Data Spaces (A4DS) - ([04bece3](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/04bece33e891549a49b0697393591ebc72c76571)) - Wannes Kerckhove
- [**breaking**] Implemented ORM layer for persisting POJOs. Added identity to storage records. - ([651a4d7](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/651a4d74a0ad396824bd166471dc82a8908988ce)) - Wannes Kerckhove
- **(ui)** show icon for literals in change request records - ([61cc606](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/61cc6067ea55f1b2ad2cd7123aeca27919206edc)) - Thomas Dupont
- **(ui)** show datatype in change request results - ([b922a96](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/b922a965cdab1b99f2004ca30d2852b91b544ff0)) - Thomas Dupont
- **(ui)** Name is no longer required when creating a slice - ([4cb38ed](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/4cb38eddf2dc7153f4be8385ded367959ae9c675)) - Thomas Dupont
- **(ui)** new slices now have a simple template to start with - ([d169738](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/d16973841afd17655337e5c5a15cbe8c9d782ad1)) - Thomas Dupont
- **(ui)** improved error body handling - ([9d298a2f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/-/commit/9d298a2fed78ac629d616b9eb154f9b4923faf49)) - Thomas Dupont
### Bug Fixes
- **(ui)** removing kss prefix from context no longer breaks Slice edit/create - ([1a6e95a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/1a6e95a9a0550fdc649184bd75b400c42bbc974e)) - Thomas Dupont

---

## [0.9.2](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/v0.9.1..v0.9.2) - 2025-09-03
### Bug Fixes
- Fixed broken @generateMutations for types with explicit @predicate directives instead of prefix-based qualifications. - ([21b8c13](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/21b8c136eac09a44a47c9043ad46a45e7dc1905f)) - Wannes Kerckhove
- IRI validation should check if the IRI is an absolute IRI based on a set of known schemes. - ([7e40c1b](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/7e40c1bdeb29c1e5a292bc5708580370e928db57)) - Wannes Kerckhove
### Improvements
- Improved how errors are outputted for the GraphQL endpoints (we used to serialize the entire stack trace as JSON, which is not readable but also caused issues with the GraphiQL client getting stuck). - ([7e40c1b](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/7e40c1bdeb29c1e5a292bc5708580370e928db57)) - Wannes Kerckhove

---

## [0.9.1](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/v0.9.0..v0.9.1) - 2025-08-26
### Bug Fixes
- Fixed GraphQL & Slice regression bugs - ([2bdaae4](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/2bdaae445f75445d39c8cd88fc24d95a610c1a0b)) - Wannes Kerckhove

---

## [0.9.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.8.0..0.9.0) - 2025-08-22
### Bug Fixes
- Add /robots.txt to default exclude path prefixes - ([ad32257](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/ad32257440be011ce1594d726a8f4b4d0a285f20)) - Thomas Dupont
- Made ingesting RDF files more robust, fixes #4, #5 - ([c4d0008](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/c4d0008b6aa44be810e45d82fd3eea716d0eeef4)) - Wannes Kerckhove
-  Fixed FQN not properly being resolved for input types when validating a Slice change request - ([57a04b7](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/57a04b7a2e9bc12fedec74ba8083d1f2304405b9)) - Wannes Kerckhove
### Features
- Slice name is now optional - ([469a2c5](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/469a2c5cd4e0202cd93a145ba5eb7c6be1b8186c)) - Wannes Kerckhove
- QoL improvements when authoring Slices - ([57a04b7](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/57a04b7a2e9bc12fedec74ba8083d1f2304405b9)) - Wannes Kerckhove
- Added generate-client bootstrap config property that enables enforcing PKCE for public clients. - ([9caeb38](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/9caeb3847ac8aacb2a1ef56b8f2c640b48adef61)) - Wannes Kerckhove

---

## [0.8.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.7.0..0.8.0) - 2025-07-24

### Bug Fixes

- **(openapi)** check if openapi components is not null, before compacting jsonld response and examples - ([e691b90](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/e691b90a6f0b5749ca0dce5f7543a311951fdc46)) - Thomas Dupont
- Predicate or Class directive should take precedence when deriving FQ name for GraphQL schema element. - ([015aa6a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/015aa6a9a227f387d16157fb2d1dc7a58f5d049b)) - Wannes Kerckhove

### Documentation

- openapi filter to add `@context` and compaction for jsonld responses and examples to the API docs - ([441a160](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/441a1601a596b86da81349ea87e0c265e7afa59a)) - Thomas Dupont

### Features

- [**breaking**] fine grained access control added (openfga authz integration) - ([4a0f9ad](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/4a0f9ad41e89716f9dc26379727c1119473f2797)) - Wannes Kerckhove
- **(ui)** Kvasir UI is now part of the same codebase - Thomas Dupont
- **(ui)** Kvasir UI is now started alongside the monolith (on path `/_ui`) - Thomas Dupont
- **(ui)** UI added to manage Access Control of your pod - Thomas Dupont
- openapi filter to automatically add `@context` and compaction to the API docs - ([0af9184](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/0af9184de869be53dfaa08fe3346ae247a72f176)) - Thomas Dupont
- also add `@context` when prefixes are detected - ([e706805](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/e70680576a793ae74d802e6e1804d35482ec5a06)) - Thomas Dupont
- also add `@context` on single items - ([f846837](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/f8468376af2593516f303f0381cf2c09d108d096)) - Thomas Dupont

### Refactoring

- **(openapi)** replace log.debug with log.trace statements - ([a9256ff](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a9256ff896231b1e99a44dcc893ba258e097e2d4)) - Thomas Dupont

---

## [0.7.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.6.4..0.7.0) - 2025-06-23

### Features

- **(ui)** All json and graphql fields are now monaco-editor fields - ([439ffa5](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/439ffa5d39117572503b51a0a9d0f2c490155525)) - Thomas Dupont

### Miscellaneous Chores

- **(deploy)** various helmfile fixes for local and CNL deployment - ([c6f6c76](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/c6f6c761f9163e7bf7eecedcba0a51a95bb3ba11)) - Jasper Vaneessen
- **(helm)** fix kvasir image registry - ([fa3328c](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/fa3328c95c9b0618c1a2ca42355bc495bf01fe5d)) - Jasper Vaneessen
- **(helm)** fix tag and registry overrides - ([fc2d113](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/fc2d113536b649d61bae4fa2e118500d1c5ca60e)) - Jasper Vaneessen
- **(helm)** default SA for kvasir UI - ([dd97390](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/dd97390d9be97ac98ed81509f504fe1de00466e1)) - Jasper Vaneessen
- **(helm)** facilitate deploy from new repo and add config options - ([5a2e26a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/5a2e26ac109e5733cba1c024d1ccd5f9389d0de2)) - Jasper Vaneessen
- **(ui)** version bump (without default tag) - ([0525efc](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/0525efc5fa53ec9b75c729061dbe48b13b44f0bd)) - Thomas Dupont
- initial commit of kvasir-ui code - ([c4b0b42](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/c4b0b425edc109bc4d13ca26c5e8dc92c2d09d67)) - Thomas Dupont
- move kvasir-ui chart and change helmfile entry - ([6d29363](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/6d2936335404e8628cbc8dc54783ed07ac6229a7)) - Jasper Vaneessen
- Added/reworked docs, fixed some API inconsistencies - ([fbd87e6](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/fbd87e68bda2a2308575652b538f875ac5be25ac)) - Wannes Kerckhove

---

## [0.6.4](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.6.3..0.6.4) - 2025-05-27

### Bug Fixes

- fix Fix for #30, but causes test failures - ([5b2b194](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/5b2b19440e5d926454fda55520241397fe11e53c)) - Wannes Kerckhove
- Fix for tests failures - ([964706d](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/964706d3c977489ed9a0c92f2b37986d23fd7848)) - Wannes Kerckhove

### Miscellaneous Chores

- bumped docker-compose monolith version - ([3035cdb](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/3035cdb8df87cf1dd1f4587ae6ca56fcf302e213)) - Wannes Kerckhove

---

## [0.6.3](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.6.2..0.6.3) - 2025-05-26

### Bug Fixes

- Fixed #29 - ([e8bd804](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/e8bd804401c61f4b6f198197e398f4f78b92104d)) - Wannes Kerckhove

### Miscellaneous Chores

- bumped docker compose monolith image version - ([c0d0b38](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/c0d0b380a87a80672c9844dad32f177326d7cc1f)) - Wannes Kerckhove

---

## [0.6.2](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.6.1..0.6.2) - 2025-05-22

### Bug Fixes

- Fix for #20, FQN not being used due to missing parentheses - ([3382a7a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/3382a7a972f88629efd68911ef0d5e020bcf6d31)) - Wannes Kerckhove
- Fixed change request validator when multiple GraphQL input fields map to the same relation. - ([7e05027](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/7e05027b6fb46f35c5bd887a0b6c81cd8b599a91)) - Wannes Kerckhove

### Miscellaneous Chores

- updated docker compose image ref - ([c812c68](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/c812c68c695aed5535a430aa081db3bd3ed9d380)) - Wannes Kerckhove

---

## [0.6.1](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.6.0..0.6.1) - 2025-05-13

### Bug Fixes

- Hotfix for messages in the change.requests topic not getting acked - ([74d6c62](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/74d6c62b9a22fa3905b62def2474d6228f63a6f5)) - Wannes Kerckhove

---

## [0.6.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.5.1..0.6.0) - 2025-05-12

### Bug Fixes

- Fixed formatting of JSON-LD change request SSE output - ([8a161a0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/8a161a07d2932d9ae46ada61a3133c785e902147)) - Wannes Kerckhove
- Fix for #18 - ([71f9ee9](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/71f9ee96eec8384810f0edd2e2f58a91793e223b)) - Wannes Kerckhove
- Various fixes to pagination (also works with GraphQL variables now) - ([cc7401e](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/cc7401e76a78a1656232b47af463332d56bec01d)) - Wannes Kerckhove
- Fixed tests (default management test-port clashed with minio docker compose port) - ([6974413](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/697441388770c02ea5aa5683ae5bca2340864b6d)) - Wannes Kerckhove
- Fixes #23 - ([716476d](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/716476dbc8cfa6bebf3d08e431004ed034762c4d)) - Wannes Kerckhove
- Fix for #24 - ([898279f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/898279f2009101480eab8457d70444c483de0936)) - Wannes Kerckhove
- Mismatch of change request id bound timestamp and storage timestamp - ([c59a4a8](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/c59a4a8f6eef70ee2580ee5b0436489c77b5ec3f)) - Wannes Kerckhove
- Fixed test util config - ([ad04c85](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/ad04c8569cfb630a0fb5256ec70e2474f04edf22)) - Wannes Kerckhove
- Fixed GraphQL string array argument parsing - ([187cf21](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/187cf212c7b17c4ce1a6c73e65732b1c517c6ea6)) - Wannes Kerckhove
- Fixed fqPodId for change request SSE - ([6b4eccd](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/6b4eccd12675a8e8ce077c9ac54226e6c92dcdf4)) - Wannes Kerckhove
- Fixed loading change records for Slice change requests. Report now also includes error message - ([ee8c425](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/ee8c4256a5302a671b6505091461781c3a19ea06)) - Wannes Kerckhove
- Fixed and optimized GraphQL subscription streaming - ([6114876](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/6114876c2888e471cfcd915f1e4bb239bd67d3cd)) - Wannes Kerckhove
- Node filter can now refer to a variable (fixes #26) - ([2f6933f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/2f6933f964c96969f3301fc0e54fdde22db8df17)) - Wannes Kerckhove
- Fixed \_rawRDF implementation (closes #27) - ([ad1f7bd](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/ad1f7bda57427ab622050cab35b833db511ca104)) - Wannes Kerckhove

### Features

- added support for preconfigured clients (to be generated during Keycloak setup) - ([3a48e8a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/3a48e8af1d2d6ffbea17dbb7dee7f00b7312c56d)) - Wannes Kerckhove
- Support for s,p.o,g filter when querying change records - ([87a2212](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/87a2212ab5a308c253866262b3fd85d6527608ad)) - Wannes Kerckhove
- enable GraphQL query endpoint via GET for Slices - ([e1b1b71](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/e1b1b7145055322245fb9a70f70ce9cf2cb89a47)) - Wannes Kerckhove
- Added support for nested orderBy statements - ([40d5917](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/40d591707f21d0e326273566aef41cf64afe12ad)) - Wannes Kerckhove

### Miscellaneous Chores

- added additional prefixes to alice default config - ([5b2fa08](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/5b2fa0893c2d6807cd6071197ae558c2a598f2fd)) - Wannes Kerckhove
- [**breaking**]Switched to GraphQL based Slice input validation (instead of SHACL). - ([9e6c4fc](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/9e6c4fc0e98ad208c279bc28a341b17626817a19)) - Wannes Kerckhove

---

## [0.5.1](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.5.0..0.5.1) - 2025-05-02

### Bug Fixes

- **(compose)** clickhouse version back to 24.8.11.5 - ([006cfb4](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/006cfb4305c6f87bccfaeb8900bd70895a65bef0)) - Thomas Dupont
- **(writerside)** use up to date docker version for writerside building - ([bfd0ab5](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/bfd0ab5747ed5a080bb300dc79905dd2f780b5e1)) - Thomas Dupont
- KvasirUriInfo abstraction allows running Kvasir behind a reverse proxy - ([967796b](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/967796b4f1fb604343d842cdbd152f7a4bcef63c)) - Wannes Kerckhove
- wrong package for ClickhouseClientConfig fixed - ([61f1085](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/61f1085474665c1c4b08c883ed7af22f6d34361b)) - Thomas Dupont
- uploading s3 files (special chars) no longer crashes simple rdf pipeline - ([10a0f15](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/10a0f15f510f59a97d613acdaff00c4eccac227f)) - Thomas Dupont

### Documentation

- update changelog - ([6864186](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/68641864866c7d81327f533d632fc4ac1fd2032e)) - Jasper Vaneessen
- Add documentation on Kubernetes deployments - ([dd997f3](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/dd997f3af3fc852b3918a926e090d47d58c21e6e)) - Jasper Vaneessen

### Miscellaneous Chores

- **(dependencies)** set CH version to Altinity LTS 24.8 - ([47ac5a0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/47ac5a0fcff1194e244ae65b0e44261a2fb3fd9b)) - Jasper Vaneessen
- **(deps)** update docker images - ([df53928](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/df5392834db3c5314a5db4653d554461c147add7)) - Jasper Vaneessen
- **(helm)** update appVersion - ([eab7c70](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/eab7c70ed4f15875c869bf07b03a615f3f4b20c7)) - Jasper Vaneessen
- **(kubernetes)** Add Helm and Helmfile configurations along with helper scripts for Kubernetes deployments - ([21a53f6](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/21a53f648a296e3cccc468f306288adee9333c53)) - Jasper Vaneessen
- **(release)** prepare release 0.5.1 - ([c329bb0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/c329bb0e28efecb5ce239c0c1624a4ed4783adb6)) - Jasper Vaneessen
- **(release)** clean up changelog - ([56695a8](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/56695a8fe8144412ac05b13bb53bd4981d7710d7)) - Jasper Vaneessen
- update dependencies - ([5f0ee38](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/5f0ee38783a66a6cb9138ed3fbfcd58459f95e57)) - Jasper Vaneessen
- update changelog [skip ci] - ([607fde4](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/607fde4ef0919ee6d1b77cb413596390109a2957)) - Jasper Vaneessen

---

## [0.5.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.4.0..0.5.0) - 2025-04-16

### Bug Fixes

- **(kafka)** use kafka-native as devservice instead of redpanda - ([c782485](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/c782485848b16ca73a27f7f8dc203bf6e5d3bce5)) - Thomas Dupont
- fixes ci issues with different auth plugins - ([2f45ffc](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/2f45ffc6b766cdc05e81ebd2b74ad68d9e80d564)) - Thomas Dupont
- Fixed issue when querying using inline fragments - ([0a98da8](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/0a98da8a4d83ff41de9738874f3f5d0c689b253a)) - Wannes Kerckhove
- Added missing exception mapper for GraphQL SchemaProblem - ([1aac504](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/1aac5047fb8fce980e76bcb1a10359ebdab6cd38)) - Wannes Kerckhove
- Fixed faulty type filter when a GraphQLOutputType has no implementing types in a Slice - ([540967d](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/540967d359fe33471b6bf4d0c4f23768b921aba3)) - Wannes Kerckhove

### Features

- redirect to kvasir-ui on text/html pod url - ([bf60bfa](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/bf60bfa2c2ddb1b980ea0c1b6493184f5c031797)) - Thomas Dupont
- don't redirect if no kvasir.webclient_uri is set - ([43e6187](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/43e6187e984f8a24de10255ede3578a6670d3346)) - Thomas Dupont
- Pod registration now tries to initialize auth config with policy enforcement provider (if no config is specified) 169f7c - ([77ad271](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/77ad2713c40c638a9c897a3c68b8d9affd262a53)) - Thomas Dupont
- auth redirect feature - ([b1f63c8](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/b1f63c8fe41459ebc7fd43de048460194f3e7fa3)) - Thomas Dupont
- [**breaking**]Updated GraphQL type-system, fixed support for inline fragments and added discoverability features. - ([daa724f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/daa724f741c8e567b1ddb9c61f61b888ba61460b)) - Wannes Kerckhove
- [**breaking**]Expanded Kvasir events - ([2cab8c6](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/2cab8c6e5ea65f64dac0689c364197d6a806b49b)) - Wannes Kerckhove
- Slices now support user-defined type hiearchies. Fixed minor bug when requesting literal via \_object field. - ([9ee60eb](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/9ee60eb157f808c1aab092469576b3624cb830cc)) - Wannes Kerckhove

### Miscellaneous Chores

- **(compose)** set monolith and kvasir-ui versions to 0.4.0 - ([efc86b7](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/efc86b72bd5418914666bdf89c4d1989ff8e5154)) - Thomas Dupont
- **(compose)** set kvasir-ui version to 0.4.1 - ([cf01848](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/cf018486f6eb428715c4a96d116747ce2dd6fa55)) - Thomas Dupont
- **(compose)** update kvasir-ui to 0.4.2 release - ([882e3b5](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/882e3b516a11a757ab379c495114ef5c19a7e240)) - Thomas Dupont
- **(compose)** update kvasir-ui to 0.4.3 release - ([1652ddd](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/1652ddd3adb2902cd0ab593d6ac37e09142cab76)) - Thomas Dupont
- **(compose)** update kvasir-ui to 0.4.4 release - ([a6c2a9d](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a6c2a9dd769dc050cfc61d3dba24ceeb2739d93c)) - Thomas Dupont
- **(compose)** prepare for monolith 0.4.1 release - ([a5f9d0c](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a5f9d0cf99f3ed7f32e886962a5f92849146be73)) - Thomas Dupont
- **(release)** update changelog - ([6444cda](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/6444cda56387c8bf501ba0d716fa193d5f801e00)) - Jasper Vaneessen
- fix changelog gitlab URLs - ([44a9dc2](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/44a9dc23afb691233247094f0c3f5857dfd7327f)) - Jasper Vaneessen

---

## [0.4.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.3.0..0.4.0) - 2025-03-17

### Documentation

- initial docs for kvasir ui - ([dbdf8e5](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/dbdf8e5838d0b05a32e6770b897be02703d02e78)) - Thomas Dupont
- updated images - ([c760d7f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/c760d7fa0b4814edda5701a6ec640b56716e5f6c)) - Thomas Dupont
- extra kvasir ui docs - ([5fc17ef](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/5fc17ef3b2f8bb4d285b78802f8a909b3767b493)) - tdupont

### Features

- GraphQL subscription support - ([b010e0f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/b010e0f4122c8c398d9d909d2a3c23397f94c950)) - Wannes Kerckhove
- exception behaviour for change processor is now configurable (shutdown on failure remains the default) - ([5e8560b](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/5e8560be066562a983d3af63bff34918c7ea6964)) - Wannes Kerckhove

### Miscellaneous Chores

- **(compose)** add postgres healthcheck - ([2c6ba0e](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/2c6ba0ee7b1c7573d8e0ea48fedb5a4df3145a25)) - Jasper Vaneessen
- **(compose)** pin PostgreSQL and Keycloak images versions - ([f8c30c6](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/f8c30c6b049b85f66986458448e7c134aabb3605)) - Jasper Vaneessen
- update ui to 0.3.4 (fix s3 special prefix chars) - ([5e95e49](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/5e95e49f543734fbd0fa77236263c81699920d63)) - tdupont

### Tests

- Added basic unit tests for the main services - ([f169cbe](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/f169cbee5a2183f0720e19be94f73d0c2ba9a814)) - Wannes Kerckhove

---

## [0.3.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.2.0..0.3.0) - 2025-02-13

### Bug Fixes

- delete slices targets to correct table again - ([616dd8d](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/616dd8d2cabf175e066c7b15c6bd58bec066c7a9)) - tdupont
- docker compose versions for monolith and kvasir-ui were wrong - ([7f96ab5](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/7f96ab53e0226690642c1c21466bf1a0be0decc5)) - tdupont

### Documentation

- authentication docs - ([ed3c44b](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/ed3c44b79e94daee6a3c0f55a4bd2a5e6909a8a2)) - tdupont

### Features

- set more sensible auth timeout values - ([936cb1f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/936cb1ffdd5e30437645e1da2d1933a25ff5445e)) - tdupont
- [**breaking**]introduced Custom data backends (incl. SAREF timeseries PoC) - ([43f9b2f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/43f9b2fbd3d5ad3d6d865de8cbbf57e8a5ef6209)) - Wannes Kerckhove

### Miscellaneous Chores

- **(compose)** pin kvasir and kvasir-ui versions - ([571d1eb](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/571d1ebb64b97c1d9a152269eb2817d962459dd2)) - Jasper Vaneessen
- update ui to 0.3.3 - ([2b0bbd2](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/2b0bbd2f61812c2ab78b31790299f87476f13c6b)) - Thomas Dupont
- prepare update kvasir to 0.3.0 - ([6be1093](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/6be10939f294f9c024f0bcdee49aa593393ce8f6)) - Thomas Dupont

---

## [0.2.0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.1.10..0.2.0) - 2025-01-13

### Bug Fixes

- .deployment docker-compose.yml - ([859f93f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/859f93f9556d59bddc6013ef88441935071350a2)) - tdupont
- 409 on reboot when realm already exists, no longer crashes kvasir - ([fe83e94](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/fe83e94d699acc58825011264e518bfe58d1b749)) - tdupont
- everything under /q/ is reachable again in dev mode - ([bfa5db2](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/bfa5db2a327ecd12576dcececba4f67cd29f4787)) - tdupont

### Features

- add SmallRye health checks to all services + readiness check for monolith initialization - ([90c92f0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/90c92f00f28ffdbe822613e04f753954b92c25ff)) - Jasper Vaneessen

### Miscellaneous Chores

- **(compose)** run kvasir in host networking mode - ([ad55314](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/ad553147dcf0f610fef292be2375b8ce1dc88323)) - tdupont
- docs updated and code cleanup - ([a252762](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a252762b50e87d3cc0c013e0d09e7103a5e6677d)) - tdupont
- update kvasir-ui to 0.2.3 - ([a1bc6ff](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a1bc6fff950f9dd86bf26dcde81aa2f264883333)) - tdupont
- update changelog - ([5d8db89](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/5d8db89e559c5a881b0690be4a4b29704f59604a)) - Jasper Vaneessen

---

## [0.1.10](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.1.9..0.1.10) - 2025-01-08

### Bug Fixes

- set cors for dev - ([020ca33](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/020ca332d4814ea54a90c18814519b1c59744804)) - Thomas Dupont
- allow suspicious types (CH) and use minio/minio image - ([e4eef5a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/e4eef5a89c4230c6580ef1f8c4e43e9c3424d1ab)) - Jasper Vaneessen
- added missing quarkus auth permission config - ([73ba618](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/73ba618d49298c3f06bb22959f91d2a7889596b3)) - Wannes Kerckhove
- Fixed some issues with Keycloak client init - ([0fcdadf](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/0fcdadf2232079d4737b92e3b9c39decb83cf1fe)) - Wannes Kerckhove
- FixedKeycloakPolicyEnforcerAuthorizer should not be active in CDI when keycloak policy-enforcer is disabled - ([ef2f99d](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/ef2f99d666f45c3657db8c3834813be991f757fd)) - Wannes Kerckhove
- refer to new path of quarkus-realm.json - ([413a2ea](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/413a2eae8ed61391de6d135b6e7180d74511a73b)) - Thomas Dupont
- Fixed Slice creation failing (and updated wrong example in docs) - ([959a083](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/959a083393e64c335a50ec3bcec58ef0ba0f2ed0)) - Wannes Kerckhove
- Fixed disabling policy-enforcer not working properly. Policy-enforcer is now disabled by default when running tests - ([4089c75](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/4089c75d3406ce39e633d9c0db97e51f04a03543)) - Wannes Kerckhove
- removed hardcoded url references to keycloak and ui - ([70f14cd](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/70f14cdc1c89358d7add430a916350f1edfb4c71)) - Thomas Dupont
- added missing keycloak config to docker compose (deployment) - ([54ab1f5](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/54ab1f50602798553f03dd4d7766d4d4c3450529)) - Thomas Dupont
- ui redirect uri port set to 8081 - ([a96242d](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a96242d8db8f6e4b9d694d0948e200c077686226)) - Thomas Dupont

### Features

- [**breaking**]Cursor-paging for the GraphQL API, expanded JSON-LD content negotiation for the GraphQL API - ([8e602b7](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/8e602b712187c7509a3ee164d1edc3d30967c9d8)) - Wannes Kerckhove
- setup per pod Keycloak realms automatically in dev mode - ([79bef46](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/79bef461cf908bc1088f60ecb90d09d1a7d036aa)) - Wannes Kerckhove
- proposal for public pod overview and public profile - ([d89a20f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/d89a20fb2b5a62afb3917bbf5e6edf93a0a6c842)) - Wannes Kerckhove
- add kvasir-ui client generation to keycloak pod auth init - ([d5bce68](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/d5bce6816fc055fee9dc8bb0a6c07632410d4db2)) - tdupont
- default user accounts for demo pods - ([0f3ac6c](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/0f3ac6ce05009baa800399937cf624ec31eb52d5)) - Thomas Dupont

### Miscellaneous Chores

- **(dependencies)** update docker image dependencies (devservices+compose) and pin exact versions - ([0a8ce9c](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/0a8ce9c17deeb5e5b0d47f20cb2f92f67be4650d)) - Jasper Vaneessen
- **(deps)** update docker images - ([506ce62](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/506ce62ca81c7f23616c4c35d6663cce128d1ccf)) - Jasper Vaneessen
- **(renovate)** group CI and Docker categories - ([6d50315](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/6d50315438a6ef9c98f64a8b3e7cbf1b60d7760d)) - Jasper Vaneessen
- fix renovate - ([21bd702](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/21bd702b8dea3f882bf7e4231d9bcf66a330f553)) - Jasper Vaneessen
- update renovate config - ([643bc50](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/643bc503124ffc307b3fd0af954a5fd2fc6d57f9)) - Jasper Vaneessen
- add changelog - ([b7cc599](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/b7cc59985549266ff535a8c50ab34c3faca25aaf)) - Jasper Vaneessen
- master to main - ([f46ed3c](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/f46ed3c7729c77b4e1bb84c1762ec2b5b383dbf8)) - Jasper Vaneessen
- follow CH LTS version 24.8 - ([43f2c62](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/43f2c6272164d06813d319edcfd94bedb68fed79)) - Jasper Vaneessen
- move quarkus-realm.json to resources folder - ([131e04b](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/131e04bc4065f1fd21e1a770289354e472f9eb02)) - Thomas Dupont
- kvasir-ui 0.2.2 with keycloak auth in .deployment docker compose - ([5c0a34c](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/5c0a34c3e31487f558dd589df4e633f58c98fedd)) - Thomas Dupont

### Tests

- removed test scope dependency on clickhouse-plugin in storage-api (no longer needed because of build property disabling policy-enforcer) - ([bab321b](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/bab321bd292323bd28f5a52181f73676f10ac496)) - Wannes Kerckhove

---

## [0.1.9](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.1.8..0.1.9) - 2024-12-05

### Bug Fixes

- Added extra checks to prevent non-fully-qualified IRIs from being processed as valid change records - ([3de88fc](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/3de88fcd7687ca7b02065f94c04cd3b41925a7af)) - Wannes Kerckhove
- Fixed provided context not being used for querying (always used the pod's default context) - ([057ab86](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/057ab868f5743152401e7c301ef12af6d8344455)) - Wannes Kerckhove

### Features

- added support for filtering on named-graphs when querying using GraphQL (see `@graph` directive) - ([27422d0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/27422d09978506a58d2dc99f12a7deffb0524d7c)) - Wannes Kerckhove
- Changes API now supports the JSON-LD '@reverse' keyword - ([d8942d7](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/d8942d7905981af18f374dd1018cece70e415269)) - Wannes Kerckhove

---

## [0.1.8](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.1.7..0.1.8) - 2024-12-03

### Bug Fixes

- Fixed issue with S3 proxy (caused by breaking change in Vert.x dependency) - ([ae16c0d](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/ae16c0dfc976aa8eb45316abefb2871c1bcb1079)) - Wannes Kerckhove
- Fixed runtime errors caused by build issues - ([875c9d7](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/875c9d705853af2b1adccc31fb569dc384765208)) - Wannes Kerckhove
- Fixed rdfs_Resource queries no longer giving results - ([80c03f7](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/80c03f7d5505ea106db9a1770093aa0794b39392)) - Wannes Kerckhove

---

## [0.1.7](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.1.6..0.1.7) - 2024-11-28

### Bug Fixes

- Changes API now support JSON-LD named graphs - ([921c4c2](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/921c4c26ee9413a27d61a5058895c97179fd2247)) - Wannes Kerckhove
- Fixed issue with namespaces when executing GraphQL queries without a supplied context - ([17415d8](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/17415d8305806edff38572cdec39adc3861ec258)) - Wannes Kerckhove
- expose Link header through CORS for paging - ([9e2dcc5](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/9e2dcc5986be11b2fa07e2af606c9fba0218f5d8)) - Thomas Dupont
- change kvasir-ui port in dev docker compose to 3000 instead of 8081 - ([ae3f823](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/ae3f8230a0965d81a26dd67ec08c7b285b93e931)) - Thomas Dupont

### Documentation

- Updated getting started in docs to reflect repo README.MD - ([3fcb0dc](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/3fcb0dc58279edabe8ba9042b6c970f41bb2766d)) - Wannes Kerckhove
- added missing link to repo in getting started - ([b5c5a41](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/b5c5a41c0b3223262ecf526a36981a0154dd740d)) - Wannes Kerckhove
- Updated docs to reflect migration to Maven - ([313e653](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/313e653e6df72c8bf5c34ae4b8983ddd54c49c44)) - Wannes Kerckhove

### Features

- added first kvasir-ui image in docker compose - ([454fc7f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/454fc7f89a48e1c93b98c507c08ae4ac35c03edb)) - Thomas Dupont
- introducing an @graph directive to specify which graph to query (no implementation yet) - ([940e795](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/940e795a8a1d9ae14261af8204fa2bcf1b8227eb)) - Wannes Kerckhove

### Miscellaneous Chores

- aadd healthcheck to demo compose - ([a1fca8f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a1fca8f49d26542912a14883c87e08cef1bc7fb8)) - Jasper Vaneessen
- cleanup - ([f74a9bf](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/f74a9bfdd787ba187185072b71ddcf771066fc1e)) - Wannes Kerckhove
- use port 8081 for the kvasir-ui - ([0518cf9](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/0518cf990ffe56536e102f8deec0dec6828eddfe)) - Thomas Dupont
- removed gradle wrapper and gradle build files - ([4450a16](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/4450a16ce60714e1abdbc6c51371bd2f6b9efad3)) - Wannes Kerckhove
- added maven wrapper - ([1ec1e67](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/1ec1e67c5fcec0088632d88f558d0653973d7be4)) - Wannes Kerckhove
- added Maven build files - ([d98d914](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/d98d914545c226de11fa6a237d08c1585665c893)) - Wannes Kerckhove
- updated .gitignore - ([a86b02d](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a86b02da6e176469f17a5f83aeadd9b6853d92fc)) - Wannes Kerckhove

---

## [0.1.6](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.1.5-preview..0.1.6) - 2024-11-25

### Bug Fixes

- possible fix for S3 delete hanging - ([58fe8a8](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/58fe8a89a31c93d8f25f7b77bb7a6a18ae6cd26a)) - Wannes Kerckhove

---

## [0.1.5-preview](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.1.4-preview..0.1.5-preview) - 2024-11-25

### Bug Fixes

- CORS handler should also allow all when not running in dev-mode (by default) - ([e2f1689](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/e2f1689239f1812e6bb3065fe27fc9fc9179eeb4)) - Wannes Kerckhove

---

## [0.1.4-preview](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.1.3-preview..0.1.4-preview) - 2024-11-25

### Bug Fixes

- Fixed change request trigger upon S3 deletion of an RDF file (also introduced Bucket versioning) - ([149e1f7](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/149e1f70b944798e474a689d9a9f77b177c8517d)) - Wannes Kerckhove
- Fixed error while viewing swdemo change records (due to unsupported language tag) - ([bd674d1](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/bd674d1a101b2f948d247ba8d0a80fba28e5a49d)) - Wannes Kerckhove
- FIxed pagination in GraphQL for nested fields - ([3a1cec4](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/3a1cec4a263c8a9c7c29487c21cf319a9b5a8d6b)) - Wannes Kerckhove
- Fixed some issues with Slices - ([d0f7e3a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/d0f7e3add9c19751c38324a754423188fc7fe5e6)) - Wannes Kerckhove
- Fixed current pod config properties not showing via pod management API - ([661645c](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/661645ce3cd15fac982a1ad5543b21c486c5fd37)) - Wannes Kerckhove
- Fixed pod management pod config update - ([e1e1ced](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/e1e1ced3e88f12455a20b556387c453c39c88fff)) - Wannes Kerckhove

### Features

- implemented pagination for change history (internal implementation is offset based and should be improved in the future) - ([64d8f41](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/64d8f4118c22560e530a181df6efe271f29f8c16)) - Wannes Kerckhove
- implemented basic offset based paging for GraphQL querying (cfr. Stardog or Ruben T implementations) - ([83021fb](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/83021fbeff940edfb7a276025dc495c533ae944e)) - Wannes Kerckhove
- totalCount is now available for non-scalar relationships - ([8000fbf](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/8000fbfd4384eec60d52bb91e863170b5b6d86cd)) - Wannes Kerckhove

---

## [0.1.3-preview](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.1.2-preview..0.1.3-preview) - 2024-11-20

### Bug Fixes

- prevent the creation of a Slice if the name already exists - ([8319ca1](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/8319ca1d8fc72c5f9dcaaac5a45361d1bf667a95)) - Wannes Kerckhove

### Documentation

- Updated README.MD (to include a section on running with docker compose) - ([0bcbe54](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/0bcbe547c9e543edea1fa540f89b39a78207fa70)) - Wannes Kerckhove
- Updated readme - ([8cb229d](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/8cb229d6cd6dd813c16505ab8849d3361ee0b08e)) - Wannes Kerckhove
- updated docs (documented request language-tag when querying) - ([a2bb8c2](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a2bb8c24bd0dc114ba10d198553ab4ead6148a08)) - Wannes Kerckhove

### Features

- Query engine now support context language tag - ([58a59f0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/58a59f063ac368cdb4f60dcde96f40ef9cbe83c0)) - Wannes Kerckhove

### Miscellaneous Chores

- removing old xtdb startup scripts - ([272b286](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/272b286e9f4d511c51ae908a454f7214db6c801c)) - Wannes Kerckhove
- removed legacy xtdb code in kg implementation module - ([025f330](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/025f33007590e4194ca4fa46bbc061a86e716760)) - Wannes Kerckhove

---

## [0.1.1-preview](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/compare/0.1.0-preview..0.1.1-preview) - 2024-11-19

### Bug Fixes

- fixed prefix name for Clickhouse config - ([421ccf8](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/421ccf8e51c4b2bd81b2a02cb36f75af858d8521)) - Wannes Kerckhove

---

## [0.1.0-preview] - 2024-11-19

### Bug Fixes

- **(doc)** algolia vars wrongly quoted - ([16a3733](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/16a3733d44199bf345604fccf1c809fd775af219)) - tdupont
- **(doc)** set style explicilty on images in paragraph - ([34c609e](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/34c609ed7f0749110796232c0b4043d6fb429ab4)) - tdupont
- Fixed #1 - ([26caa9f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/26caa9f4d481adcfe67fa65d3bc96c889963e3ba)) - Wannes Kerckhove
- filtering on value using field argument now works for 'id' fields as well. - ([7a94fbe](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/7a94fbe2709bada7653d0da5c324327cd1b86e26)) - Wannes Kerckhove
- Fixed nested GraphQL queries when fields contain upercase characters - ([8a0200e](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/8a0200e24550ecefc5cc2661766faecec9472c86)) - Wannes Kerckhove
- Fixed insert/delete templates using where for ChangeRequest + feat: Additional delete options - ([75ce301](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/75ce301abe79457d7f5d0711851afeb780c1ddf5)) - Wannes Kerckhove
- .gitlab-ci should be at the root level - ([325b5b2](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/325b5b29207d59cb1c8a3e67387d868a6c70fd7b)) - tdupont
- cors header removed form minio response (quarkus already does cors) - ([fa08b27](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/fa08b27f0cad2a19f56be03aa7f0e2fe315c0f49)) - tdupont
- decode path and query before aws signing - ([f0e67dd](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/f0e67dd40b625854c320c1b9c85fbe9fa239503a)) - Thomas Dupont
- Fixed wrong object id being passed on via Kafka - ([93b87b0](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/93b87b0c80ffe52d3f91b5225f5064c9f1497c1d)) - Wannes Kerckhove
- on empty query, default to empty string for aws signing url - ([4749bdf](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/4749bdf0dce2412da98b7f69613a5505d6f4919f)) - tdupont
- on empty query, default to empty string for aws signing url - ([bf488ab](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/bf488ababb49e92cf6efa76d46d3619467d0ef33)) - tdupont
- graphql empty errors array should be undefined - ([db91600](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/db916001125c553f1b0ba69795e06cce6b1222e6)) - Thomas Dupont
- Fixed getting id for external resource - ([3476f46](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/3476f46f3dbdd70e49bb029629766ca0d57aea75)) - Wannes Kerckhove
- Fixed id filter - ([4e2cea2](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/4e2cea2251f055d676bc814b74e65d05660c3701)) - Wannes Kerckhove
- Fix for predicate target data loading exceeding Clickhouse field value size (limited Dataloader batching) - ([ae899d5](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/ae899d548fc07b30d70b7fda443cd888ecfc8f2d)) - Wannes Kerckhove
- [GraphQL] Fixed issues when no variables are used - ([68f6de2](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/68f6de28e043d0d9f12139d9aadeef248e573252)) - Wannes Kerckhove
- Streaming endpoint for changes should just be /changes with a different Accept (text/event-stream) header - ([bfd0c7e](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/bfd0c7ee391202b2a881f45bf03c4cbe60dca604)) - Wannes Kerckhove

### Documentation

- Added basic readme with usage examples - ([2a08907](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/2a08907c01beca4e0cb6bc7d72dc4c01ad6954c6)) - Wannes Kerckhove
- small changes to readme - ([cd269b3](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/cd269b38d6c7eba24c0fec429c10322c71815a88)) - Wannes Kerckhove
- Added link to Xtdb - ([662e944](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/662e94429a72497067e9a40b628bf111564acf52)) - Wannes Kerckhove
- Include docs for latest features - ([bcb2f17](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/bcb2f17d491b755a0b9615f255e1bd21360a0778)) - Wannes Kerckhove
- Updated openapi/swagger documentation - ([b7d1e7a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/b7d1e7ab95a71b857fd53cffaa92f50ee0bba78b)) - Wannes Kerckhove
- Working on setting up Writerside docs - ([1c89344](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/1c89344d969247078dfdd3e94d44cb9395071978)) - Wannes Kerckhove
- provided basic documentation - ([66009c8](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/66009c8883ecd3c993f53c7b7b303265c1dcdd47)) - Wannes Kerckhove
- small fixes - ([f664f84](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/f664f844ee524ac80ef522c505504d3df062ca23)) - Wannes Kerckhove
- small tweaks - ([d1b2edd](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/d1b2edd1c5e17684bb129f352df819518ca08549)) - tdupont
- algolia search added - ([267003a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/267003adcc9a9213c560c69c1fa60bad370951ca)) - tdupont
- updated docs to reflect Query API changes - ([3ef9b8a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/3ef9b8a7e8d2d09416dc2ccf07b47d067dcc709b)) - Wannes Kerckhove
- updated Inbox docs to reflect changes to Querying - ([710cf22](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/710cf22d2b541a18f1ee91436ac4942d802cd03f)) - Wannes Kerckhove
- Added some content to architecture + a motivation for using GraphQL. - ([c958d5f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/c958d5f6a5439852933fed2686bc736044b0e020)) - Wannes Kerckhove
- Added stream flow diagram - ([21ad494](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/21ad494d87561dfc2b00e3634ee3d879bc68dbbe)) - Wannes Kerckhove
- updating docs - ([07d5ef3](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/07d5ef3a0db84a4e45b8d2b4c03fd2a74bcc1a9d)) - Wannes Kerckhove
- updating docs - ([b284ed4](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/b284ed40b7aab975d4526659b8147c8456bcf5fc)) - Wannes Kerckhove

### Features

- added additional query features while fixing some bugs - ([96f6eac](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/96f6eac8c47265dd100a46f8867ea0b90457ec14)) - Wannes Kerckhove
- Implemented S3 low-level storage API - ([84b1267](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/84b126796e1ac1c24c6b94338af324d0c2cd7114)) - Wannes Kerckhove
- GraphQL queries now support namespaces prefixes (using underscore as separator) - ([6d64ba9](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/6d64ba9971fb8e8f699151f9f91e43d21e46d2c9)) - Wannes Kerckhove
- GraphQL query API updates with introspection field '\_\_fieldnames' to list possible predicate IRIs for a specific selection. - ([6be4aac](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/6be4aac5033e70239795d766432c607e50ee2ae2)) - Wannes Kerckhove
- added support for additional selection criteria as GraphQL field arguments - ([947e873](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/947e873c623d4de2d75c4046761a1ef79418217b)) - Wannes Kerckhove
- specify target graph when performing inbox or query requests + query endpoint can now also return JSON-LD directly (based on context supplied in request) - ([7819ffe](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/7819ffe0e1a17b4a3dfc25c44101161afaf47af7)) - Wannes Kerckhove
- ChangeRequests (inbox API) now support assertions and GraphQL based insert/delete templates - ([b9456a5](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/b9456a5b1474d3371883c4fb95fde597f95ea0a6)) - Wannes Kerckhove
- update via delete/insert with bindings ChangeRequest is now available - ([2f6e13f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/2f6e13f5dc6fae117405824b61b4fcd1786be25c)) - Wannes Kerckhove
- Implemented special GraphQL field totalCount - ([786b84e](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/786b84e96a3cfe8d54d65de0fe329daeeb63df65)) - Wannes Kerckhove
- added kafka channel initializers so explicit cdi imports are not required in each module - ([c3b6223](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/c3b6223696fa2e5a0037e7569cfa8949c3f5d859)) - Wannes Kerckhove
- started working on slices (subgraphs) - ([f75ca7f](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/f75ca7f3e46b5fa8102e639042f606b25eb2f420)) - Wannes Kerckhove
- updated GraphQLToSQL conversion to support top-level type entry-points - ([bdf2394](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/bdf239474009616b82d55c5a5f1824a131ce72f0)) - Wannes Kerckhove
- Support for basic GraphQL introspection and fixed some issues with updated QL - ([e5c07a5](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/e5c07a5c7d9cc8a4ac9ad5afd9fd132e8c59a2e6)) - Wannes Kerckhove
- QL support for array arguments, having IN semantics - ([0cbcc9a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/0cbcc9a6d13ea6309d7e3fb8bb97b27ff0b95d4b)) - Wannes Kerckhove
- implemented RSQL expression based @filter directives - ([6a89f26](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/6a89f26222e3825fc4374c5fa88b926e07c2e204)) - Wannes Kerckhove
- Introspection works. KG can be navigated an interacted with using GraphiQL - ([1978b07](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/1978b07baf22cc592b9cd0ea1231d1d758d380eb)) - Wannes Kerckhove
- default prefix mapping is now part of pod config (instead of fetching a fixed standard mapping file). - ([11201bf](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/11201bf1d4fc1b91307f1f5a951b9e48130a364a)) - Wannes Kerckhove
- made Amazon content signature optional for s3 storage requests, which makes the API easier to use. - ([b6b4223](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/b6b42237c7858bd955ead83fc09bdb8aa88029ba)) - Wannes Kerckhove
- ChangeRequests can now contain references to internal S3 storage with objects to delete/insert - ([91cd8a7](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/91cd8a79002f86ab58713a77e7741d79195be984)) - Wannes Kerckhove
- new query engine based on a preconstructed schema - ([01637a3](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/01637a362990522e5bcede002699690cd8125735)) - Wannes Kerckhove
- Implemented Slice store for Clickhouse - ([9657aea](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/9657aeada69602993947e46a868f7c5f57c75146)) - Wannes Kerckhove
- initial poc implementation of Slice API - ([3297f1a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/3297f1a0b16ed7907fdaef9713956924bff38962)) - Wannes Kerckhove
- kvasir-ui now in docker compose file (port 8081) - ([a104ba4](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a104ba479069e0a831ddaf1e804271ba64a91be3)) - tdupont
- implemented an HTTP body interceptor, allowing a more generic approach to how we handle JSON-LD request/response bodies. - ([99d7822](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/99d782287b27f1cc1eead3b849482c64fed93429)) - Wannes Kerckhove
- added support for GraphQL variables - ([097ce56](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/097ce56962cbe22736322b53b92f53e65a5658ef)) - Wannes Kerckhove
- added notifications for Slice changes - ([fd2bf1b](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/fd2bf1bd72645ca8360f53e33f2b55859aab1fde)) - Wannes Kerckhove
- added notifications for Slice changes - ([556c5cf](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/556c5cfcfb80d5cbea10243fb33ffb3087335090)) - Wannes Kerckhove
- added improved implementation of the CH data fetcher - ([19cb8b1](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/19cb8b192cc83e36a15b39932d57af368ea81d2b)) - Wannes Kerckhove
- pod management API - ([56470cc](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/56470cc27d9f22ae85d17f22f22f600cba6dcf54)) - Wannes Kerckhove
- Implemented Query API time travel - ([8b1498a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/8b1498aaf94f6f55f89fe6c33dd29c5f42d36c49)) - Wannes Kerckhove

### Miscellaneous Chores

- removed clickhouse-kg module as a CH implementation is not needed atm - ([b2bdee9](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/b2bdee996bba8865432d19111e5ec9c2bf974f78)) - Wannes Kerckhove
- added license - ([a0b7d54](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/a0b7d5439f55334c912f05d3fafb74a70ca6fd21)) - Wannes Kerckhove

### Refactoring

- renamed ChangeRequest where field to with - ([303431a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/303431a92838d1d233a327db76eeda979c6cadcd)) - Wannes Kerckhove

### Tests

- added storage api (s3 proxy) basic test - ([082de4d](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/082de4d0d046ec4d9ed405b03738ab9e45a2154a)) - Wannes Kerckhove
- Wrote some basic tests for Xtdb KG mutations - ([155323a](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/commit/155323aaca01ad6db24628d42c3e501cf7edfccb)) - Wannes Kerckhove

<!-- generated by git-cliff -->
