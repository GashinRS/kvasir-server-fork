package main

values: {
	deploymentMode: "monolith"
	serviceName:    "monolith"

	image: {
		repository: "gitlab.ilabt.imec.be:4567/kvasir/kvasir-server/monolith"
		tag:        "latest"
		pullPolicy: "IfNotPresent"
	}
}
