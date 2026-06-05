# Fields with multiple types

<show-structure depth="2"/>

GraphQL union types cannot include scalar values. RDF, however, often allows a property to contain either an IRI
reference or a literal value, and sometimes multiple literal data types.

For Slice schemas, you can model these cases using the Kvasir common types [`RDFNode`](Querying.md#rdfnode) and
[`BoxedLiteral`](Querying.md#boxedliteral-implements-rdfnode).

## IRI-or-literal fields with `RDFNode`

For example, the manufacturer of an instrument can be represented either as an IRI referring to a resource, or as a
literal String value. To model both options in a Slice schema, use `RDFNode`:

```graphql
type Query {
    instruments: [ex_Instrument]
}

type Mutation {
    add(instruments: [InstrumentInput!]): ID!
}

type ex_Instrument {
    ex_manufacturer: RDFNode
}

input InstrumentInput @class(iri: "ex:Instrument") {
    id: ID!
    manufacturer: String @predicate(iri: "ex:manufacturer")
    manufacturerRef: ID @predicate(iri: "ex:manufacturer")
}
```

The mutation supports inserting the manufacturer as either a literal String value (using `manufacturer`) or as an IRI
(using `manufacturerRef`).

When adding data through the Slice Changes API (`POST /{podId}/slices/{sliceId}/changes`) instead of GraphQL, both
representations are also accepted by the Slice validator for the same predicate.

To retrieve the manufacturer value, use the `_rawRDF` property:

```graphql
{
  instruments {
    id
    ex_manufacturer {
      _rawRDF
    }
  }
}
```

Returns:

```json
{
  "data": {
    "instruments": [
      {
        "id": "http://example.org/instruments/1",
        "ex_manufacturer": {
          "_rawRDF": {
            "@value": "Fender",
            "@type": "http://www.w3.org/2001/XMLSchema#string"
          }
        }
      },
      {
        "id": "http://example.org/instruments/2",
        "ex_manufacturer": {
          "_rawRDF": {
            "@id": "https://www.espguitars.com"
          }
        }
      }
    ]
  }
}
```

## Multi-literal fields with `BoxedLiteral`

The same approach can be used when a property may have multiple literal data types. If the field should be literal-only
(no IRI references), use `BoxedLiteral`.

For example, if `ex:price` can be either a decimal or a String:

```graphql
type Query {
    instruments: [ex_Instrument]
}

type Mutation {
    add(instruments: [InstrumentInput!]): ID!
}

type ex_Instrument {
    ex_manufacturer: RDFNode
    ex_price: BoxedLiteral
}

input InstrumentInput @class(iri: "ex:Instrument") {
    id: ID!
    manufacturer: String @predicate(iri: "ex:manufacturer")
    manufacturerRef: ID @predicate(iri: "ex:manufacturer")
    priceString: String @predicate(iri: "ex:price")
    priceDecimal: Float @predicate(iri: "ex:price")
}
```

<seealso>
    <category ref="related">
        <a href="Slices.md">Slices</a>
        <a href="Querying.md">Query API</a>
    </category>
</seealso>

