| Measure | Repeats | Planning ms | Execution ms | Buffers hit/read, top node (per repeat) | Planning buffers hit | Relations scanned |
|---|---|---|---|---|---|---|
| GET by id, flat orders | 3 | 0.090-0.188 | 0.016-0.025 | 4/0; 4/0; 4/0 | 47; 47; 47 | [1] |
| GET by id, orders_p (no created_at in the predicate: every partition is probed) | 3 | 0.646-1.209 | 0.103-0.358 | 39/0; 39/0; 39/0 | 513; 513; 513 | [15] |
| GET by id with created_at, orders_p (pruning possible) | 3 | 0.038-0.040 | 0.007-0.008 | 4/0; 4/0; 4/0 | n/a | [1] |
| range: orders created in the last 7 days, flat | 3 | 0.035-0.043 | 57.350-57.556 | 2785/28621; 2977/28429; 3169/28237 | 8; 8; 8 | [1] |
| range: orders created in the last 7 days, orders_p | 3 | 0.279-0.298 | 5.052-7.434 | 317/0; 317/0; 317/0 | 4; 4; 4 | [3] |
| relay batch (oldest 100 unpublished), flat outbox | 3 | 0.083-0.151 | 1.242-9.551 | 226/0; 232/0; 240/0 | 19; 19; 19 | [1] |
| relay batch (oldest 100 unpublished), outbox_p | 3 | 0.469-0.779 | 1.054-2.440 | 158/0; 165/0; 171/0 | 253; 253; 253 | [15] |
