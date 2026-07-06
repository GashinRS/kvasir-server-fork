package templates

import (
	corev1 "k8s.io/api/core/v1"
)

// #ManagedSecret builds a Kubernetes Secret resource for a single integration
// from inline values discovered in `applicationConfig`. Wrapper definition:
// inputs are top-level params, the resulting Secret is exposed as `out`.
//
// Why a wrapper rather than `corev1.#Secret & {#config: ...}`? `corev1.#Secret`
// is a closed schema; even definition-prefixed (`#`) helper fields are
// rejected when unified with it. Keeping params outside the Secret struct
// avoids that constraint and cleanly separates inputs from output.
//
// Why bypass `timoniv1.#Metadata`? It cross-references `metadata.name` in a
// regex-validated label (`app.kubernetes.io/name`). When the wrapper is
// instantiated lazily inside a `for` comprehension, the evaluator flags the
// label value as non-concrete even though `name` resolves fine. Building the
// metadata map directly here matches the Kubernetes Secret schema without
// triggering that evaluation order.
#ManagedSecret: {
	#config:      #Config
	#integration: string
	#fields: [string]: #SecretField

	let _name = (#ManagedSecretName & {instanceName: #config.metadata.name, integration: #integration}).out

	out: corev1.#Secret & {
		apiVersion: "v1"
		kind:       "Secret"
		type:       corev1.#SecretTypeOpaque
		metadata: {
			name:      _name
			namespace: #config.metadata.namespace
			labels: {
				for k, v in #config.metadata.labels {(k): v}
				"app.kubernetes.io/name":       #config.metadata.name
				"app.kubernetes.io/version":    #config.moduleVersion
				"app.kubernetes.io/managed-by": "timoni"
				"app.kubernetes.io/component":  "secret-\(#integration)"
			}
			if #config.metadata.annotations != _|_ {
				annotations: #config.metadata.annotations
			}
		}
		stringData: {
			for fieldName, meta in #fields {
				if meta.value != _|_ {
					"\(fieldName)": meta.value
				}
			}
		}
	}
}
