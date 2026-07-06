package main

values: {
	deploymentMode: "microservices"

	image: {
		tag:        "latest"
		pullPolicy: "IfNotPresent"
	}

	init: {
		enabled:        true
		exitAfterSetup: true
	}

	ingress: {
		enabled: true
		host:    "kvasir.localhost"
	}
}
