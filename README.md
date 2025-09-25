SELECT *
FROM process_state
WHERE transaction_id = '1026911-C934-49a4-8a15-3730a20e309b'
ORDER BY step ASC;

SELECT *
FROM transaction_state
WHERE uetr = '13ce487a-8108-4108-9510-e25ed32a62551';

SELECT *
FROM transaction_state
WHERE transaction_id = '1C291240-bla1-9a34-9612-bOcead2039';

SELECT *
FROM submit_request
WHERE transaction_id = '<TRANSACTION_ID_ORIGINAL>';

SELECT *
FROM payload
WHERE transaction_id = '<TRANSACTION_ID_ORIGINAL>';

SELECT *
FROM safestore
WHERE transaction_id = 'adz5b8b1-cd16-4980-8390-d627cL1047314';

SELECT *
FROM rules_y2
WHERE id = 'CBPR_AM_OB'
  AND description LIKE '%YaGMG%';

SELECT *
FROM rules_nodes
WHERE rule_id LIKE 'CBPR_SG_IB_CATALL_SCE'
ORDER BY rank ASC;

SELECT *
FROM rules_nodes
WHERE rule_id LIKE 'CBPR_SC_GPI_OB_3390748'
ORDER BY rank ASC;

SELECT *
FROM rules
WHERE id LIKE '%358074%';

SELECT *
FROM rules_nodes
WHERE rule_id LIKE 'CBPR_%_OB'
  AND ode_id = 'act-pollb-mt-message-enrichers';

SELECT *
FROM rules_nodes
WHERE verb NOT LIKE 'GeneratedUETRA';