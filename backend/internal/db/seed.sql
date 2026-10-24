-- Synthetic seed data for the MRO Mirror. Brands are real manufacturers so the
-- catalog reads naturally, but every SKU, part number, price, stock level,
-- customer and contract here is invented for study purposes.

-- ---------------------------------------------------------------- Categories
INSERT INTO pim.categories (id, parent_id, name, unspsc, attribute_schema) VALUES
('motors', NULL, 'Motors', '26101100', '[]'),
('motors-ac', 'motors', 'General Purpose AC Motors', '26101112', '[
  {"key":"hp","label":"Horsepower","type":"number","unit":"HP","facet":true},
  {"key":"voltage","label":"Voltage","type":"enum","facet":true},
  {"key":"phase","label":"Phase","type":"enum","facet":true},
  {"key":"rpm","label":"Nameplate RPM","type":"number","facet":true},
  {"key":"nema_frame","label":"NEMA Frame","type":"enum","facet":true},
  {"key":"enclosure","label":"Enclosure","type":"enum","facet":true},
  {"key":"shaft_dia_in","label":"Shaft Dia.","type":"number","unit":"in","facet":true},
  {"key":"washdown","label":"Washdown Rated","type":"bool","facet":true}
]'),
('fasteners', NULL, 'Fasteners', '31161500', '[]'),
('fasteners-hex-cap', 'fasteners', 'Hex Head Cap Screws', '31161501', '[
  {"key":"thread_size","label":"Thread Size","type":"enum","facet":true},
  {"key":"tpi","label":"Threads per Inch","type":"number","facet":true},
  {"key":"length_in","label":"Length","type":"number","unit":"in","facet":true},
  {"key":"grade","label":"Grade","type":"enum","facet":true},
  {"key":"material","label":"Material","type":"enum","facet":true},
  {"key":"finish","label":"Finish","type":"enum","facet":true},
  {"key":"pack_qty","label":"Pack Qty","type":"number","facet":false}
]'),
('fasteners-hex-nut', 'fasteners', 'Hex Nuts', '31161601', '[
  {"key":"thread_size","label":"Thread Size","type":"enum","facet":true},
  {"key":"tpi","label":"Threads per Inch","type":"number","facet":true},
  {"key":"grade","label":"Grade","type":"enum","facet":true},
  {"key":"finish","label":"Finish","type":"enum","facet":true},
  {"key":"pack_qty","label":"Pack Qty","type":"number","facet":false}
]'),
('ppe', NULL, 'Safety & PPE', '46181500', '[]'),
('ppe-cut-gloves', 'ppe', 'Cut-Resistant Gloves', '46181536', '[
  {"key":"ansi_cut_level","label":"ANSI/ISEA 105 Cut Level","type":"enum","facet":true},
  {"key":"size","label":"Glove Size","type":"enum","facet":true},
  {"key":"coating","label":"Palm Coating","type":"enum","facet":true},
  {"key":"food_safe","label":"Food Contact Safe","type":"bool","facet":true}
]'),
('ppe-eyewear', 'ppe', 'Safety Glasses', '46181802', '[
  {"key":"ansi_z87","label":"ANSI Z87.1 Rating","type":"enum","facet":true},
  {"key":"lens_tint","label":"Lens Tint","type":"enum","facet":true},
  {"key":"anti_fog","label":"Anti-Fog","type":"bool","facet":true}
]'),
('chemicals', NULL, 'Chemicals', '12161900', '[]'),
('chem-degreasers', 'chemicals', 'Degreasers & Cleaners', '47131810', '[
  {"key":"container","label":"Container","type":"enum","facet":true},
  {"key":"size_oz","label":"Size","type":"number","unit":"oz","facet":true},
  {"key":"nsf_rating","label":"NSF Rating","type":"enum","facet":true},
  {"key":"flash_point_f","label":"Flash Point","type":"number","unit":"°F","facet":false}
]'),
('chem-lubricants', 'chemicals', 'Lubricants', '15121500', '[
  {"key":"form","label":"Form","type":"enum","facet":true},
  {"key":"size_oz","label":"Size","type":"number","unit":"oz","facet":true},
  {"key":"nsf_rating","label":"NSF Rating","type":"enum","facet":true}
]'),
('electrical', NULL, 'Electrical', '39120000', '[]'),
('elec-safety-switches', 'electrical', 'Safety Switches (Disconnects)', '39121601', '[
  {"key":"amps","label":"Amps","type":"number","unit":"A","facet":true},
  {"key":"voltage","label":"Voltage","type":"enum","facet":true},
  {"key":"poles","label":"Poles","type":"number","facet":true},
  {"key":"fusible","label":"Fusible","type":"bool","facet":true},
  {"key":"nema_rating","label":"NEMA Enclosure Rating","type":"enum","facet":true}
]'),
('elec-breakers', 'electrical', 'Circuit Breakers', '39121616', '[
  {"key":"amps","label":"Amps","type":"number","unit":"A","facet":true},
  {"key":"poles","label":"Poles","type":"number","facet":true},
  {"key":"voltage","label":"Voltage","type":"enum","facet":true},
  {"key":"interrupt_ka","label":"Interrupt Rating","type":"number","unit":"kA","facet":true}
]'),
('pt', NULL, 'Power Transmission', '26111700', '[]'),
('pt-bearings', 'pt', 'Ball Bearings', '31171504', '[
  {"key":"bore_mm","label":"Bore","type":"number","unit":"mm","facet":true},
  {"key":"od_mm","label":"Outside Dia.","type":"number","unit":"mm","facet":true},
  {"key":"width_mm","label":"Width","type":"number","unit":"mm","facet":true},
  {"key":"seal","label":"Seal Type","type":"enum","facet":true},
  {"key":"series","label":"Series","type":"enum","facet":true}
]'),
('pt-vbelts', 'pt', 'V-Belts', '26111802', '[
  {"key":"section","label":"Belt Section","type":"enum","facet":true},
  {"key":"outside_length_in","label":"Outside Length","type":"number","unit":"in","facet":true}
]');

-- ---------------------------------------------------------------- Products
INSERT INTO pim.products (sku, category_id, name, brand, mfr_part_number, description, list_price, uom, weight_lb, attributes, hazmat_un, hazmat_class, sds_url, drop_ship_only) VALUES
('4KW71','motors-ac','General Purpose Motor, 1 HP, 3-Phase, 1760 RPM, 143T, TEFC','Baldor-Reliance','EM3546T','NEMA Premium efficiency foot-mounted motor for pumps, fans and conveyors.',612.00,'EA',38,'{"hp":1,"voltage":"208-230/460","phase":"3","rpm":1760,"nema_frame":"143T","enclosure":"TEFC","shaft_dia_in":0.875,"washdown":false}',NULL,NULL,NULL,false),
('4KW72','motors-ac','General Purpose Motor, 2 HP, 3-Phase, 1755 RPM, 145T, TEFC','Baldor-Reliance','EM3558T','NEMA Premium efficiency motor, cast iron frame.',798.00,'EA',46,'{"hp":2,"voltage":"208-230/460","phase":"3","rpm":1755,"nema_frame":"145T","enclosure":"TEFC","shaft_dia_in":0.875,"washdown":false}',NULL,NULL,NULL,false),
('4KW80','motors-ac','Washdown Motor, 1 HP, 3-Phase, 1765 RPM, 143T, TENV Stainless','Baldor-Reliance','CSSWDM3546T','Stainless steel washdown duty motor for food and beverage lines; IP69K.',1245.00,'EA',44,'{"hp":1,"voltage":"208-230/460","phase":"3","rpm":1765,"nema_frame":"143T","enclosure":"TENV Washdown","shaft_dia_in":0.875,"washdown":true}',NULL,NULL,NULL,false),
('6XH12','motors-ac','General Purpose Motor, 1 HP, 3-Phase, 1755 RPM, 143T, TEFC','WEG','00118ET3E143T','W22 premium efficiency motor, rolled steel frame.',486.00,'EA',35,'{"hp":1,"voltage":"208-230/460","phase":"3","rpm":1755,"nema_frame":"143T","enclosure":"TEFC","shaft_dia_in":0.875,"washdown":false}',NULL,NULL,NULL,false),
('6XH15','motors-ac','General Purpose Motor, 5 HP, 3-Phase, 1760 RPM, 184T, TEFC','WEG','00518ET3E184T','W22 premium efficiency motor for heavy duty applications.',1089.00,'EA',78,'{"hp":5,"voltage":"208-230/460","phase":"3","rpm":1760,"nema_frame":"184T","enclosure":"TEFC","shaft_dia_in":1.125,"washdown":false}',NULL,NULL,NULL,false),
('3PW58','motors-ac','General Purpose Motor, 1/2 HP, 1-Phase, 1725 RPM, 56, ODP','Leeson','110081.00','Capacitor-start motor for light industrial and HVAC duty.',289.00,'EA',22,'{"hp":0.5,"voltage":"115/208-230","phase":"1","rpm":1725,"nema_frame":"56","enclosure":"ODP","shaft_dia_in":0.625,"washdown":false}',NULL,NULL,NULL,false),
('3PW60','motors-ac','General Purpose Motor, 3/4 HP, 1-Phase, 1725 RPM, 56, TEFC','Leeson','113898.00','Totally enclosed capacitor-start motor.',356.00,'EA',26,'{"hp":0.75,"voltage":"115/208-230","phase":"1","rpm":1725,"nema_frame":"56","enclosure":"TEFC","shaft_dia_in":0.625,"washdown":false}',NULL,NULL,NULL,false),
('2N865','motors-ac','General Purpose Motor, 1 HP, 3-Phase, 1760 RPM, 143T, TEFC','Tenmile','TM-1P143T-B','Private label premium efficiency motor; value alternative to national brands.',429.00,'EA',36,'{"hp":1,"voltage":"208-230/460","phase":"3","rpm":1760,"nema_frame":"143T","enclosure":"TEFC","shaft_dia_in":0.875,"washdown":false}',NULL,NULL,NULL,false),
('5HZ20','motors-ac','General Purpose Motor, 10 HP, 3-Phase, 1770 RPM, 215T, TEFC','Baldor-Reliance','EM2333T','Large frame motor shipped direct from the manufacturer.',1980.00,'EA',168,'{"hp":10,"voltage":"208-230/460","phase":"3","rpm":1770,"nema_frame":"215T","enclosure":"TEFC","shaft_dia_in":1.375,"washdown":false}',NULL,NULL,NULL,true),

('4NHL2','ppe-cut-gloves','Cut-Resistant Gloves, ANSI A2, Nitrile Foam, Size L','Ansell','11-518-9','HyFlex lightweight cut-resistant glove for assembly work.',5.85,'PR',0.2,'{"ansi_cut_level":"A2","size":"L","coating":"Nitrile Foam","food_safe":false}',NULL,NULL,NULL,false),
('4NHL3','ppe-cut-gloves','Cut-Resistant Gloves, ANSI A2, Nitrile Foam, Size XL','Ansell','11-518-10','HyFlex lightweight cut-resistant glove for assembly work.',5.85,'PR',0.2,'{"ansi_cut_level":"A2","size":"XL","coating":"Nitrile Foam","food_safe":false}',NULL,NULL,NULL,false),
('5TFP1','ppe-cut-gloves','Cut-Resistant Gloves, ANSI A4, Polyurethane, Size L','Ansell','11-542-9','HyFlex mid-weight glove for sheet metal handling.',9.40,'PR',0.2,'{"ansi_cut_level":"A4","size":"L","coating":"Polyurethane","food_safe":false}',NULL,NULL,NULL,false),
('5TFP2','ppe-cut-gloves','Cut-Resistant Gloves, ANSI A4, Polyurethane, Size XL','Ansell','11-542-10','HyFlex mid-weight glove for sheet metal handling.',9.40,'PR',0.2,'{"ansi_cut_level":"A4","size":"XL","coating":"Polyurethane","food_safe":false}',NULL,NULL,NULL,false),
('7MXR1','ppe-cut-gloves','Cut-Resistant Gloves, ANSI A6, Uncoated, Food Safe, Size L','Tenmile','TM-CG6-FS-L','FDA-compliant blue HPPE glove for meat and poultry processing.',12.75,'PR',0.3,'{"ansi_cut_level":"A6","size":"L","coating":"Uncoated","food_safe":true}',NULL,NULL,NULL,false),
('7MXR3','ppe-cut-gloves','Cut-Resistant Gloves, ANSI A9, Steel Core, Size L','Tenmile','TM-CG9-L','Highest cut level for glass and blade handling.',28.40,'PR',0.4,'{"ansi_cut_level":"A9","size":"L","coating":"Nitrile Sandy","food_safe":false}',NULL,NULL,NULL,false),
('3MSG1','ppe-eyewear','Safety Glasses, Clear Anti-Fog Lens, Z87+','3M','SF401AF','SecureFit 400 series with pressure diffusion temples.',6.20,'EA',0.1,'{"ansi_z87":"Z87+","lens_tint":"Clear","anti_fog":true}',NULL,NULL,NULL,false),
('3MSG2','ppe-eyewear','Safety Glasses, Gray Anti-Fog Lens, Z87+','3M','SF402AF','SecureFit 400 series, gray lens for outdoor use.',6.45,'EA',0.1,'{"ansi_z87":"Z87+","lens_tint":"Gray","anti_fog":true}',NULL,NULL,NULL,false),

('1DGR4','chem-degreasers','Brake & Parts Cleaner, Non-Chlorinated, 14 oz Aerosol','CRC','05089','Fast-evaporating cleaner; flammable aerosol.',8.95,'EA',1.1,'{"container":"Aerosol","size_oz":14,"nsf_rating":"None","flash_point_f":-4}','UN1950','2.1','/sds/1DGR4.pdf',false),
('1DGR7','chem-degreasers','Heavy Duty Cleaner/Degreaser, 1 gal','Simple Green','13412','Water-based non-flammable degreaser for food plant floors.',24.50,'EA',9,'{"container":"Jug","size_oz":128,"nsf_rating":"A1","flash_point_f":0}',NULL,NULL,'/sds/1DGR7.pdf',false),
('9KL33','chem-degreasers','Citrus Solvent Degreaser, 1 gal','Zep','R03710','d-Limonene solvent degreaser; flammable liquid.',36.80,'EA',8,'{"container":"Jug","size_oz":128,"nsf_rating":"None","flash_point_f":118}','UN1993','3','/sds/9KL33.pdf',false),
('9KL40','chem-degreasers','Citrus Solvent Degreaser, 55 gal Drum','Zep','R03750','Bulk drum shipped direct from the manufacturer by hazmat LTL carrier.',1420.00,'EA',470,'{"container":"Drum","size_oz":7040,"nsf_rating":"None","flash_point_f":118}','UN1993','3','/sds/9KL40.pdf',true),
('2LUB5','chem-lubricants','Food Grade Silicone Lubricant, 10 oz Aerosol, NSF H1','CRC','03040','Incidental food contact lubricant for conveyors and packaging lines.',11.40,'EA',0.9,'{"form":"Aerosol","size_oz":10,"nsf_rating":"H1"}','UN1950','2.1','/sds/2LUB5.pdf',false),
('2LUB8','chem-lubricants','Penetrant Lubricant, 11 oz Aerosol','WD-40','300015','Specialist penetrant for rusted fasteners.',7.25,'EA',0.9,'{"form":"Aerosol","size_oz":11,"nsf_rating":"None"}','UN1950','2.1','/sds/2LUB8.pdf',false),

('2YDW3','elec-safety-switches','Safety Switch, 30A, 600VAC, 3-Pole, Fusible, NEMA 4X Stainless','Square D','H361DS','Heavy duty fusible disconnect for washdown areas.',1089.00,'EA',21,'{"amps":30,"voltage":"600VAC","poles":3,"fusible":true,"nema_rating":"4X"}',NULL,NULL,NULL,false),
('2YDW5','elec-safety-switches','Safety Switch, 60A, 600VAC, 3-Pole, Non-Fusible, NEMA 1','Square D','HU362','Heavy duty non-fusible disconnect.',412.00,'EA',15,'{"amps":60,"voltage":"600VAC","poles":3,"fusible":false,"nema_rating":"1"}',NULL,NULL,NULL,false),
('6MEB2','elec-safety-switches','Safety Switch, 100A, 240VAC, 3-Pole, Fusible, NEMA 3R','Eaton','DG323NRB','General duty outdoor disconnect.',689.00,'EA',24,'{"amps":100,"voltage":"240VAC","poles":3,"fusible":true,"nema_rating":"3R"}',NULL,NULL,NULL,false),
('1BKR6','elec-breakers','Plug-In Circuit Breaker, 20A, 1-Pole, 120/240VAC','Square D','QO120','QO miniature breaker.',14.90,'EA',0.3,'{"amps":20,"poles":1,"voltage":"120/240VAC","interrupt_ka":10}',NULL,NULL,NULL,false),
('1BKR9','elec-breakers','Plug-In Circuit Breaker, 30A, 2-Pole, 120/240VAC','Eaton','BR230','Type BR breaker.',22.60,'EA',0.5,'{"amps":30,"poles":2,"voltage":"120/240VAC","interrupt_ka":10}',NULL,NULL,NULL,false),

('6BRG1','pt-bearings','Radial Ball Bearing, 6205-2RS, 25 mm Bore, Double Sealed','SKF','6205-2RSH','Deep groove ball bearing, rubber sealed both sides.',18.40,'EA',0.3,'{"bore_mm":25,"od_mm":52,"width_mm":15,"seal":"2RS Sealed","series":"6200"}',NULL,NULL,NULL,false),
('6BRG2','pt-bearings','Radial Ball Bearing, 6206-2RS, 30 mm Bore, Double Sealed','SKF','6206-2RSH','Deep groove ball bearing, rubber sealed both sides.',22.10,'EA',0.45,'{"bore_mm":30,"od_mm":62,"width_mm":16,"seal":"2RS Sealed","series":"6200"}',NULL,NULL,NULL,false),
('6BRG3','pt-bearings','Radial Ball Bearing, 6205-2RS, 25 mm Bore, Double Sealed','Tenmile','TM-6205-2RS','Private label equivalent of the 6205-2RS.',9.80,'EA',0.3,'{"bore_mm":25,"od_mm":52,"width_mm":15,"seal":"2RS Sealed","series":"6200"}',NULL,NULL,NULL,false),
('6BRG4','pt-bearings','Radial Ball Bearing, 6205-2Z, 25 mm Bore, Double Shielded','SKF','6205-2Z','Metal shielded deep groove ball bearing for higher speeds.',16.90,'EA',0.3,'{"bore_mm":25,"od_mm":52,"width_mm":15,"seal":"2Z Shielded","series":"6200"}',NULL,NULL,NULL,false),
('3VB45','pt-vbelts','V-Belt, A45, 47 in Outside Length','Gates','A45','Hi-Power II classical wrapped V-belt.',14.25,'EA',0.4,'{"section":"A","outside_length_in":47}',NULL,NULL,NULL,false),
('3VB48','pt-vbelts','V-Belt, A48, 50 in Outside Length','Gates','A48','Hi-Power II classical wrapped V-belt.',15.10,'EA',0.4,'{"section":"A","outside_length_in":50}',NULL,NULL,NULL,false),
('3VB52','pt-vbelts','V-Belt, B52, 55 in Outside Length','Gates','B52','Hi-Power II classical wrapped V-belt.',19.85,'EA',0.6,'{"section":"B","outside_length_in":55}',NULL,NULL,NULL,false);

-- A discontinued SKU that now points at its replacement.
INSERT INTO pim.products (sku, category_id, name, brand, mfr_part_number, description, list_price, uom, weight_lb, attributes, status, replacement_sku) VALUES
('2N864','motors-ac','General Purpose Motor, 1 HP, 3-Phase, 1745 RPM, 143T, TEFC (Discontinued)','Tenmile','TM-1P143T-A','Standard efficiency motor discontinued after the NEMA Premium efficiency mandate.',399.00,'EA',36,'{"hp":1,"voltage":"208-230/460","phase":"3","rpm":1745,"nema_frame":"143T","enclosure":"TEFC","shaft_dia_in":0.875,"washdown":false}','discontinued','2N865');

-- Generated fastener families: 4 thread sizes x 4 lengths x 2 grades = 32 cap screws, 8 nut SKUs.
-- HC SKU number = size_idx*8 + length_idx*2 + grade_idx + 1  (e.g. 3/8"-16 x 1-1/2" Gr 5 = HC019)
INSERT INTO pim.products (sku, category_id, name, brand, mfr_part_number, description, list_price, uom, weight_lb, attributes)
SELECT
  'HC' || lpad((s.idx*8 + l.idx*2 + g.idx + 1)::text, 3, '0'),
  'fasteners-hex-cap',
  format('Hex Head Cap Screw, %s-%s x %s in, Grade %s, %s, Pk 50', s.size, s.tpi, l.len::float8, g.grade, g.finish),
  'Tenmile',
  format('TM-HCS-%s-%s-%s', replace(s.size, '"', ''), l.len::float8, g.grade),
  'Inch-thread hex cap screw, packaged in 50s.',
  round((s.base * (1 + l.len * 0.45) * g.mult)::numeric, 2),
  'PK', round((s.base * l.len * 0.6)::numeric, 2),
  jsonb_build_object('thread_size', s.size || '-' || s.tpi, 'tpi', s.tpi, 'length_in', l.len::float8, 'grade', 'Grade ' || g.grade,
                     'material', 'Steel', 'finish', g.finish, 'pack_qty', 50)
FROM (VALUES (0,'1/4"',20,6.10),(1,'5/16"',18,8.40),(2,'3/8"',16,11.20),(3,'1/2"',13,19.60)) AS s(idx,size,tpi,base)
CROSS JOIN (VALUES (0,1.0),(1,1.5),(2,2.0),(3,3.0)) AS l(idx,len)
CROSS JOIN (VALUES (0,'5','Zinc Plated',1.0),(1,'8','Yellow Zinc',1.55)) AS g(idx,grade,finish,mult);

INSERT INTO pim.products (sku, category_id, name, brand, mfr_part_number, description, list_price, uom, weight_lb, attributes)
SELECT
  'HN' || lpad((s.idx*2 + g.idx + 1)::text, 3, '0'),
  'fasteners-hex-nut',
  format('Hex Nut, %s-%s, Grade %s, %s, Pk 100', s.size, s.tpi, g.grade, g.finish),
  'Tenmile',
  format('TM-HN-%s-%s', replace(s.size, '"', ''), g.grade),
  'Finished hex nut, packaged in 100s.',
  round((s.base * g.mult)::numeric, 2), 'PK', 1.5,
  jsonb_build_object('thread_size', s.size || '-' || s.tpi, 'tpi', s.tpi, 'grade', 'Grade ' || g.grade, 'finish', g.finish, 'pack_qty', 100)
FROM (VALUES (0,'1/4"',20,5.20),(1,'5/16"',18,7.10),(2,'3/8"',16,9.80),(3,'1/2"',13,17.40)) AS s(idx,size,tpi,base)
CROSS JOIN (VALUES (0,'5','Zinc Plated',1.0),(1,'8','Yellow Zinc',1.6)) AS g(idx,grade,finish,mult);

-- ---------------------------------------------------------------- Cross references
INSERT INTO pim.cross_references (ref_type, ref_brand, ref_part_number, ref_norm, sku, note)
SELECT t, b, p, upper(regexp_replace(p, '[^A-Za-z0-9]', '', 'g')), sku, n FROM (VALUES
  ('oem','SKF','6205-2RS1','6BRG1','Older SKF suffix for the same rubber-sealed bearing'),
  ('oem','NTN','6205LLU','6BRG1','Functional equivalent: 25x52x15, double contact seal'),
  ('oem','NSK','6205DDU','6BRG1','Functional equivalent: 25x52x15, double contact seal'),
  ('competitor','McMaster-Carr','6384K365','6BRG1','Competitor catalog number'),
  ('competitor','MSC','06582043','6BRG1','Competitor catalog number'),
  ('oem','NTN','6206LLU','6BRG2','Functional equivalent: 30x62x16'),
  ('competitor','Fastenal','0419211','6BRG2','Competitor catalog number'),
  ('obsolete','Baldor-Reliance','M3546T','4KW71','Superseded by NEMA Premium EM3546T (EISA 2010)'),
  ('competitor','Marathon','Y364','4KW71','Equivalent 1 HP 143T TEFC 3-phase'),
  ('competitor','Applied Industrial','BAL-EM3546T','4KW71','Competitor catalog number'),
  ('obsolete','Tenmile','2N864','2N865','Discontinued SKU; replacement is premium efficiency'),
  ('oem','Ansell','11-518 Size 9','4NHL2','Size-coded manufacturer number'),
  ('competitor','MSC','56371843','5TFP1','Competitor catalog number'),
  ('competitor','Siemens','HF361S','2YDW3','Equivalent 30A 3P 600V fusible heavy duty disconnect'),
  ('competitor','Fastenal','11106440','HC030','1/2"-13 x 2" Grade 8 yellow zinc, sold in 50s'),
  ('competitor','McMaster-Carr','91257A626','HC019','3/8"-16 x 1-1/2" Grade 5 zinc'),
  ('upc','WD-40','079567300159','2LUB8','UPC on the can'),
  ('competitor','Bando','A45','3VB45','Same classical A-section belt length')
) AS x(t,b,p,sku,n);

-- ---------------------------------------------------------------- Accounts
INSERT INTO accounts.org_units (id, parent_id, kind, name, code, state, city, lat, lon) VALUES
('corp-mfp', NULL, 'corporation', 'Midwest Food Processing Co.', 'MFP', NULL, NULL, NULL, NULL),
('div-mfp-dairy', 'corp-mfp', 'division', 'Dairy Division', 'DAIRY', NULL, NULL, NULL, NULL),
('fac-mke', 'div-mfp-dairy', 'facility', 'Milwaukee Dairy Plant', 'MKE', 'WI', 'Milwaukee', 43.0389, -87.9065),
('cc-4100', 'fac-mke', 'cost_center', 'Plant Maintenance', '4100', NULL, NULL, NULL, NULL),
('cc-4200', 'fac-mke', 'cost_center', 'Sanitation', '4200', NULL, NULL, NULL, NULL),
('div-mfp-protein', 'corp-mfp', 'division', 'Protein Division', 'PROT', NULL, NULL, NULL, NULL),
('fac-ict', 'div-mfp-protein', 'facility', 'Wichita Packing Plant', 'ICT', 'KS', 'Wichita', 37.6872, -97.3301),
('cc-5100', 'fac-ict', 'cost_center', 'Maintenance', '5100', NULL, NULL, NULL, NULL),
('cc-5200', 'fac-ict', 'cost_center', 'Facilities', '5200', NULL, NULL, NULL, NULL),
('corp-atlas', NULL, 'corporation', 'Atlas Aerospace Inc.', 'ATLAS', NULL, NULL, NULL, NULL),
('div-atlas-aero', 'corp-atlas', 'division', 'Aerostructures', 'AERO', NULL, NULL, NULL, NULL),
('fac-ftw', 'div-atlas-aero', 'facility', 'Fort Worth Assembly', 'FTW', 'TX', 'Fort Worth', 32.7555, -97.3308),
('cc-7100', 'fac-ftw', 'cost_center', 'Tooling', '7100', NULL, NULL, NULL, NULL),
('cc-7200', 'fac-ftw', 'cost_center', 'Facilities MRO', '7200', NULL, NULL, NULL, NULL),
('corp-lake', NULL, 'corporation', 'Lakeshore Contractors LLC', 'LAKE', NULL, NULL, NULL, NULL),
('div-lake', 'corp-lake', 'division', 'Operations', 'OPS', NULL, NULL, NULL, NULL),
('fac-rfd', 'div-lake', 'facility', 'Rockford Shop', 'RFD', 'IL', 'Rockford', 42.2711, -89.0940),
('cc-9000', 'fac-rfd', 'cost_center', 'General', '9000', NULL, NULL, NULL, NULL);

-- Approvers first so approver_id references resolve.
INSERT INTO accounts.users (id, name, email, title, role, corporation_id, facility_id, cost_center_id, per_order_limit, monthly_limit, approver_id, certifications, badge_id) VALUES
('u-elaine','Elaine Fox','elaine.fox@mfp.example','Chief Procurement Officer','approver','corp-mfp','fac-mke','cc-4100',1000000,5000000,NULL,'{}',NULL),
('u-ruth','Ruth Adeyemi','ruth.adeyemi@mfp.example','VP Operations, Dairy','approver','corp-mfp','fac-mke','cc-4100',100000,400000,'u-elaine','{}',NULL),
('u-gary','Gary Novak','gary.novak@mfp.example','Plant Manager, Wichita','approver','corp-mfp','fac-ict','cc-5100',10000,60000,'u-elaine','{}',NULL),
('u-marcus','Marcus Bell','marcus.bell@mfp.example','Plant Manager, Milwaukee','approver','corp-mfp','fac-mke','cc-4100',10000,60000,'u-ruth','{}',NULL),
('u-dana','Dana Ortiz','dana.ortiz@mfp.example','Maintenance Planner','buyer','corp-mfp','fac-mke','cc-4100',1500,10000,'u-marcus','{}','B-1003'),
('u-luis','Luis Kim','luis.kim@mfp.example','Maintenance Electrician','technician','corp-mfp','fac-mke','cc-4100',5000,20000,'u-marcus','{LICENSED_ELECTRICIAN,HAZCOM_TRAINED}','B-1002'),
('u-tom','Tom Weller','tom.weller@mfp.example','Sanitation Lead','technician','corp-mfp','fac-mke','cc-4200',500,3000,'u-marcus','{HAZCOM_TRAINED}','B-1001'),
('u-priya','Priya Shah','priya.shah@mfp.example','Maintenance Buyer','buyer','corp-mfp','fac-ict','cc-5100',2000,15000,'u-gary','{}','B-2001'),
('u-nina','Nina Park','nina.park@atlas.example','Procurement Manager','approver','corp-atlas','fac-ftw','cc-7200',50000,250000,NULL,'{HAZCOM_TRAINED}',NULL),
('u-sam','Sam Rivera','sam.rivera@atlas.example','Tooling Buyer','buyer','corp-atlas','fac-ftw','cc-7100',3000,25000,'u-nina','{}','B-7001'),
('u-jo','Jo Martinez','jo@lakeshore.example','Owner','admin','corp-lake','fac-rfd','cc-9000',25000,100000,NULL,'{LICENSED_ELECTRICIAN}',NULL);

INSERT INTO accounts.blanket_pos (id, corporation_id, facility_id, po_number, amount_authorized, amount_consumed, valid_from, valid_to) VALUES
('bpo-mke', 'corp-mfp', 'fac-mke', 'BPO-2026-MKE-001', 25000, 18250, '2026-01-01', '2026-12-31'),
('bpo-ict', 'corp-mfp', 'fac-ict', 'BPO-2025-ICT-014', 15000, 9100, '2025-01-01', '2025-12-31'),
('bpo-ftw', 'corp-atlas', 'fac-ftw', 'ATL-BPO-7100-26', 60000, 12000, '2026-01-01', '2026-12-31');

INSERT INTO accounts.category_restrictions (corporation_id, category_id, required_cert) VALUES
('corp-mfp', 'elec-safety-switches', 'LICENSED_ELECTRICIAN'),
('corp-mfp', 'elec-breakers', 'LICENSED_ELECTRICIAN'),
('corp-atlas', 'chemicals', 'HAZCOM_TRAINED');

INSERT INTO accounts.tax_exemptions (corporation_id, state, certificate_number, exemption_type, expires_on) VALUES
('corp-mfp', 'WI', 'WI-S211-88231', 'Manufacturing', '2028-06-30'),
('corp-mfp', 'KS', 'KS-PR-74-0091', 'Ingredient or Component Part', '2026-03-31'),
('corp-atlas', 'TX', 'TX-01-339-55120', 'Manufacturing', '2027-12-31');

-- ---------------------------------------------------------------- Pricing
INSERT INTO pricing.contract_prices (corporation_id, sku, price, contract_ref, valid_to) VALUES
('corp-mfp', '4KW71', 539.00, 'MFP-MSA-2024-17', '2027-12-31'),
('corp-mfp', '4KW80', 1095.00, 'MFP-MSA-2024-17', '2027-12-31'),
('corp-mfp', '7MXR1', 10.20, 'MFP-MSA-2024-17', '2027-12-31'),
('corp-mfp', '1DGR7', 19.95, 'MFP-MSA-2024-17', '2027-12-31'),
('corp-mfp', '2LUB5', 9.60, 'MFP-MSA-2024-17', '2027-12-31'),
('corp-mfp', '6XH12', 470.00, 'MFP-MSA-2024-17', '2025-12-31'),
('corp-atlas', '6BRG1', 12.95, 'ATL-SA-2025-03', '2027-06-30'),
('corp-atlas', '6BRG2', 15.80, 'ATL-SA-2025-03', '2027-06-30'),
('corp-atlas', '2YDW5', 355.00, 'ATL-SA-2025-03', '2027-06-30');

INSERT INTO pricing.category_discounts (corporation_id, category_id, discount_pct) VALUES
('corp-mfp', 'fasteners', 25), ('corp-mfp', 'ppe', 15), ('corp-mfp', 'motors', 8), ('corp-mfp', 'pt', 10),
('corp-atlas', 'fasteners', 35), ('corp-atlas', 'chemicals', 10), ('corp-atlas', 'pt', 5),
('corp-atlas', 'electrical', 12), ('corp-atlas', 'motors', 4);

INSERT INTO pricing.volume_tiers (category_id, min_qty, discount_pct) VALUES
('fasteners', 10, 10), ('fasteners', 25, 20), ('fasteners', 100, 30),
('ppe', 12, 8), ('ppe', 72, 20),
('pt-bearings', 10, 6), ('pt-bearings', 50, 12),
('chemicals', 12, 5);

INSERT INTO pricing.tax_rates (state, rate) VALUES ('WI', 0.0500), ('KS', 0.0650), ('TX', 0.0625), ('IL', 0.0625);

-- ---------------------------------------------------------------- Fulfillment network
INSERT INTO oms.nodes (id, kind, name, city, state, lat, lon, hazmat_capable, will_call, lead_days) VALUES
('dc-min', 'dc', 'Minooka Regional DC', 'Minooka', 'IL', 41.4553, -88.2620, true, false, 0),
('dc-kc', 'dc', 'Kansas City Regional DC', 'Kansas City', 'MO', 39.0997, -94.5786, true, false, 0),
('dc-dal', 'dc', 'Dallas Regional DC', 'Dallas', 'TX', 32.7767, -96.7970, true, false, 0),
('br-mke', 'branch', 'Milwaukee Branch', 'Milwaukee', 'WI', 43.0305, -87.9500, false, true, 0),
('br-rfd', 'branch', 'Rockford Branch', 'Rockford', 'IL', 42.2600, -89.0700, false, true, 0),
('br-ict', 'branch', 'Wichita Branch', 'Wichita', 'KS', 37.6800, -97.3000, true, true, 0),
('br-ftw', 'branch', 'Fort Worth Branch', 'Fort Worth', 'TX', 32.7300, -97.3500, false, true, 0),
('sup-baldor', 'supplier', 'Baldor Drop-Ship (Fort Smith)', 'Fort Smith', 'AR', 35.3859, -94.3985, false, false, 5),
('sup-zep', 'supplier', 'Zep Drop-Ship (Atlanta)', 'Atlanta', 'GA', 33.7490, -84.3880, true, false, 4);

-- Baseline stock: DCs carry everything stocked, branches carry a thinner local assortment,
-- hazmat never sits at a branch that is not permitted to store it, drop-ship-only items live at suppliers.
INSERT INTO oms.inventory (node_id, sku, on_hand)
SELECT n.id, p.sku,
  CASE
    WHEN n.kind = 'dc' AND p.category_id = 'motors-ac' THEN 2 + abs(hashtext(n.id || p.sku)) % 12
    WHEN n.kind = 'dc' THEN 40 + abs(hashtext(n.id || p.sku)) % 400
    WHEN p.category_id = 'motors-ac' THEN abs(hashtext(n.id || p.sku)) % 4
    ELSE abs(hashtext(n.id || p.sku)) % 40
  END
FROM oms.nodes n CROSS JOIN pim.products p
WHERE n.kind IN ('dc', 'branch') AND p.status = 'active' AND NOT p.drop_ship_only
  AND (p.hazmat_un IS NULL OR n.hazmat_capable);

INSERT INTO oms.inventory (node_id, sku, on_hand) VALUES
('sup-baldor', '5HZ20', 25), ('sup-baldor', '4KW71', 60), ('sup-baldor', '4KW72', 40), ('sup-baldor', '4KW80', 18),
('sup-zep', '9KL40', 30), ('sup-zep', '9KL33', 400);

-- Hand-tuned stock so the routing scenarios in the study guide are reproducible.
UPDATE oms.inventory SET on_hand = 2 WHERE node_id = 'br-mke' AND sku = '4KW71';
UPDATE oms.inventory SET on_hand = 0 WHERE node_id = 'br-mke' AND sku IN ('4KW80', '6BRG2');
UPDATE oms.inventory SET on_hand = 1 WHERE node_id = 'dc-min' AND sku = '4KW80';
UPDATE oms.inventory SET on_hand = 4 WHERE node_id = 'dc-kc' AND sku = '4KW80';
UPDATE oms.inventory SET on_hand = 0 WHERE node_id = 'dc-dal' AND sku = '4KW80';
UPDATE oms.inventory SET on_hand = 30 WHERE node_id = 'br-mke' AND sku IN ('6BRG1', '4NHL2', '1DGR7');
UPDATE oms.inventory SET on_hand = 0 WHERE sku = '7MXR3';

-- ---------------------------------------------------------------- Procurement
INSERT INTO procurement.punchout_identities (identity, shared_secret, erp_name, corporation_id, default_user_id) VALUES
('AN01000123-MFP', 'demo-ariba-secret', 'SAP Ariba', 'corp-mfp', 'u-dana'),
('COUPA-ATLAS-77', 'demo-coupa-secret', 'Coupa', 'corp-atlas', 'u-sam');

INSERT INTO procurement.edi_partners (isa_id, name, corporation_id, default_user_id) VALUES
('ATLASAERO', 'Atlas Aerospace (Oracle EBS)', 'corp-atlas', 'u-nina'),
('MFPFOODS', 'Midwest Food Processing (SAP S/4HANA)', 'corp-mfp', 'u-marcus');

-- ---------------------------------------------------------------- KeepStock
INSERT INTO keepstock.bins (id, facility_id, cost_center_id, sku, location, min_qty, max_qty, on_hand) VALUES
('BIN-MKE-001', 'fac-mke', 'cc-4100', '6BRG1', 'Crib A / Shelf 2 / Bin 04', 6, 20, 9),
('BIN-MKE-002', 'fac-mke', 'cc-4100', '3VB45', 'Crib A / Shelf 3 / Bin 11', 4, 12, 5),
('BIN-MKE-003', 'fac-mke', 'cc-4100', 'HC019', 'Crib B / Drawer 1', 3, 10, 4),
('BIN-MKE-004', 'fac-mke', 'cc-4200', '1DGR7', 'Sanitation Chemical Cage', 4, 16, 10),
('BIN-MKE-005', 'fac-mke', 'cc-4100', '2LUB5', 'Line 3 Lube Station', 6, 24, 7),
('BIN-ICT-001', 'fac-ict', 'cc-5100', '6BRG2', 'Maintenance Crib / Rack 2', 4, 12, 6),
('BIN-ICT-002', 'fac-ict', 'cc-5100', '1BKR6', 'Electrical Room Cabinet', 10, 30, 22),
('BIN-FTW-001', 'fac-ftw', 'cc-7100', 'HC030', 'Tool Crib / Aisle 4', 5, 15, 8);

INSERT INTO keepstock.vending_machines (id, facility_id, cost_center_id, name, location) VALUES
('VM-MKE-01', 'fac-mke', 'cc-4100', 'Maintenance Shop Vending', 'Building 2, Maintenance Shop'),
('VM-MKE-02', 'fac-mke', 'cc-4200', 'Sanitation Vending', 'Building 1, Sanitation Locker Room'),
('VM-FTW-01', 'fac-ftw', 'cc-7100', 'Tooling Vending', 'Hangar 3 Tool Crib');

INSERT INTO keepstock.vending_slots (machine_id, slot, sku, on_hand, capacity, par, daily_limit) VALUES
('VM-MKE-01', 'A1', '4NHL2', 18, 24, 8, 2),
('VM-MKE-01', 'A2', '4NHL3', 10, 24, 8, 2),
('VM-MKE-01', 'B1', '3MSG1', 14, 20, 6, 1),
('VM-MKE-01', 'B2', '7MXR1', 9, 16, 6, 2),
('VM-MKE-01', 'C1', '2LUB8', 6, 12, 4, 1),
('VM-MKE-02', 'A1', '7MXR1', 12, 16, 6, 2),
('VM-MKE-02', 'A2', '1DGR4', 5, 12, 4, 1),
('VM-FTW-01', 'A1', '5TFP1', 11, 20, 6, 2),
('VM-FTW-01', 'A2', '3MSG2', 7, 20, 6, 1);

-- ---------------------------------------------------------------- Order history (current month)
-- Gives buyers realistic month-to-date spend so monthly limits can trip.
INSERT INTO oms.orders (id, user_id, corporation_id, facility_id, cost_center_id, po_number, source, status, delivery_method, fulfillment_pref, subtotal, tax, total, tax_note, created_at, governance) VALUES
('ORD-100101', 'u-dana', 'corp-mfp', 'fac-mke', 'cc-4100', 'PM-OCT-PUMPS', 'web', 'shipped', 'ship', 'ship_available', 8809.92, 0, 8809.92, 'Exempt: WI-S211-88231 (Manufacturing)', GREATEST(date_trunc('month', now()), now() - interval '6 days'), '[]'),
('ORD-100102', 'u-priya', 'corp-mfp', 'fac-ict', 'cc-5100', 'ICT-2291', 'web', 'shipped', 'ship', 'ship_available', 2917.50, 189.64, 3107.14, 'KS 6.50% (exemption KS-PR-74-0091 expired)', GREATEST(date_trunc('month', now()), now() - interval '4 days'), '[]'),
('ORD-100103', 'u-sam', 'corp-atlas', 'fac-ftw', 'cc-7100', 'ATL-55810', 'edi', 'shipped', 'ship', 'ship_complete', 17750.00, 0, 17750.00, 'Exempt: TX-01-339-55120 (Manufacturing)', GREATEST(date_trunc('month', now()), now() - interval '2 days'), '[]');

INSERT INTO oms.order_lines (order_id, line_no, sku, qty, unit_price, price_source, extended) VALUES
('ORD-100101', 1, '4KW72', 12, 734.16, 'Category discount 8%', 8809.92),
('ORD-100102', 1, '6BRG2', 150, 19.45, 'Volume tier 12% (50+)', 2917.50),
('ORD-100103', 1, '2YDW5', 50, 355.00, 'Contract ATL-SA-2025-03', 17750.00);

INSERT INTO oms.shipments (order_id, node_id, method, eta_days, eta_label, status) VALUES
('ORD-100101', 'dc-min', 'ground', 1, 'Delivered', 'shipped'),
('ORD-100102', 'dc-kc', 'ground', 1, 'Delivered', 'shipped'),
('ORD-100103', 'dc-dal', 'ground', 1, 'Delivered', 'shipped');

INSERT INTO oms.shipment_lines (shipment_id, sku, qty)
SELECT s.id, l.sku, l.qty FROM oms.shipments s JOIN oms.order_lines l ON l.order_id = s.order_id;
