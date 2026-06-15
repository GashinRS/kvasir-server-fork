// values-microservices.cue — Preset for microservices deployment mode.
//
// Usage:
//   timoni -n kvasir apply kvasir oci://<registry>/kvasir \
//     --values ./values-microservices.cue \
//     --values ./my-secrets.cue
//
// This preset:
//   - Enables microservices mode (separate deployment per service)
//   - Enables the init job for cluster bootstrap
//   - Does NOT enable ingress (add your own ingress config)
//   - Does NOT configure secrets (add secrets.*.existingSecret or manage:true)

values: {
	deploymentMode: "microservices"

	init: {
		enabled:        true
		exitAfterSetup: true
	}

	// Per-service overrides (optional). Uncomment to customize.
	// services: {
	// 	"kg-query-api": {
	// 		replicas: 2
	// 		resources: requests: {
	// 			cpu:    "100m"
	// 			memory: "256Mi"
	// 		}
	// 	}
	// 	"kg-change-processor": {
	// 		replicas: 3
	// 	}
	// }

	// Ingress (optional). Uncomment to enable Traefik IngressRoute.
	// ingress: {
	// 	enabled: true
	// 	host:    "kvasir.example.com"
	// }
}
