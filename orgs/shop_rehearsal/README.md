# shop_rehearsal

A small online shop selling handmade ceramics (mugs, bowls, planters)
thrown and fired in batches, retail and wholesale to a few cafes. A
filled copy of `orgkit/template/`; the shape and its rules are in
`docs/orgkit.md`.

| File | What it is |
| --- | --- |
| `deps.edn` | waymark10 and factorykit by local root. `clojure -M:check` runs the declaration gate; `clojure -M:conform` the conformance suite. |
| `src/shop_rehearsal/org.clj` | The module: `resources` lists the org's kinds under the domain `:shop_rehearsal`. |
| `src/shop_rehearsal/kinds/` | One file per kind: product, kiln_batch, order, payment, shipping_label, return_request, refund, breakage_claim, wholesale_account, invoice, wholesale_order, monthly_close. |
| `resources/shop_rehearsal/seed.edn` | Domains, the cast (owner and two staff), the mayor, the rehearsal's outside world, and the starting products. |
