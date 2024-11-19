package kvasir.definitions.openapi

object ApiDocConstants {
    const val JSON_LD_CONTEXT_EXAMPLE_1 = "{ \"kss\": \"https://kvasir.discover.ilabt.imec.be/vocab#\", \"ex\": \"http://example.org/\" }"
    const val JSON_LD_CONTEXT_EXAMPLE_2 = "{ \"ex\": \"http://example.org/\" }"
    const val JSON_LD_RESPONSE_EXAMPLE = """
            {
              "@context": {
                "ex": "http://example.org/"
              },
              "@graph": [
                {
                  "@id": "ex:123",
                  "ex:givenName": "Bob",
                  "ex:friends": [
                    {
                      "@id": "ex:456",
                      "ex:givenName": "Alice"
                    }
                  ]
                }
              ]
            }
        """
}