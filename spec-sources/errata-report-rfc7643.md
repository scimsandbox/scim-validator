# Errata applied to rfc7643.txt

Statuses applied: Verified. Applied edits: 18 from 15 errata. Skipped: 2.

- skipped erratum 6004 (section 8.7.1): original text found 79 times
- skipped erratum 8475 (section 6): segment count differs (part 2 of 2)

## Erratum 8361 (Verified, Technical, words) section 3.1, original lines 850-851

```diff
--- original
+++ fixed
@@ -1,2 +1,2 @@
-   common attributes.  With the exception of the "ServiceProviderConfig"
-   and "ResourceType" server discovery endpoints and their associated
+   common attributes.  With the exception of the "/ServiceProviderConfig"
+   and "/ResourceTypes" server discovery endpoints and their associated
```

## Erratum 8450 (Verified, Technical, lines) section 4.1.1., original lines 1183-1201

```diff
--- original
+++ fixed
@@ -17 +17 @@
-      *  Ensure that the value is encrypted (e.g., hashed).  See
+      *  Ensure that the value is hashed or encrypted.  See
```

## Erratum 8475 (Verified, Technical, lines) section 6, original lines 1618-1622

```diff
--- original
+++ fixed
@@ -5 +5,2 @@
-      REQUIRED.
+      This attribute has a "uniqueness" of "server" and is case-exact.
+      REQUIRED
```

## Erratum 8475 (Verified, Technical, lines) section 6, original lines 1635-1637

```diff
--- original
+++ fixed
@@ -3 +3,2 @@
-      URL of the service provider, e.g., "Users".  REQUIRED.
+      URL of the service provider, e.g., "Users".  This attribute has a
+      "uniqueness" of "server" and is case-exact.  REQUIRED
```

## Erratum 8435 (Verified, Editorial, lines) section 7, original lines 1715-1719

```diff
--- original
+++ fixed
@@ -2,4 +2,5 @@
-         "boolean", "decimal", "integer", "dateTime", "reference", and
-         "complex".  When an attribute is of type "complex", there
-         SHOULD be a corresponding schema attribute "subAttributes"
-         defined, listing the sub-attributes of the attribute.
+         "boolean", "decimal", "integer", "dateTime", "reference",
+         "binary", and "complex".  When an attribute is of type
+         "complex", there SHOULD be a corresponding schema attribute
+         "subAttributes" defined, listing the sub-attributes of the
+         attribute.
```

## Erratum 5990 (Verified, Editorial, lines) section 8.2, original lines 1943-1976

```diff
--- original
+++ fixed
@@ -8 +8 @@
-      "country": "USA",
+      "country": "US",
@@ -12,13 +11,0 @@
-
-
-
-
-
-
-
-
-Hunt, et al.                 Standards Track                   [Page 35]
-
-RFC 7643                    SCIM Core Schema              September 2015
-
-
@@ -31 +18 @@
-      "country": "USA",
+      "country": "US",
```

## Erratum 5991 (Verified, Editorial, lines) section 8.3, original lines 2169-2200

```diff
--- original
+++ fixed
@@ -7 +7 @@
-      "country": "USA",
+      "country": "US",
@@ -12,11 +11,0 @@
-
-
-
-
-
-
-Hunt, et al.                 Standards Track                   [Page 39]
-
-RFC 7643                    SCIM Core Schema              September 2015
-
-
@@ -28 +17 @@
-      "country": "USA",
+      "country": "US",
```

## Erratum 8471 (Verified, Technical, lines) section 8.7.1, original lines 3501-3541

```diff
--- original
+++ fixed
@@ -21,14 +20,0 @@
-
-
-
-
-
-
-
-
-
-Hunt, et al.                 Standards Track                   [Page 63]
-
-RFC 7643                    SCIM Core Schema              September 2015
-
-
@@ -39 +24,0 @@
-              "User",
```

## Erratum 5368 (Verified, Technical, lines) section 8.7.1, original lines 3815-3831

```diff
--- original
+++ fixed
@@ -12 +12 @@
-        "required" : false,
+        "required" : true,
```

## Erratum 8472 (Verified, Technical, lines) section 8.7.1, original lines 4005-4021

```diff
--- original
+++ fixed
@@ -17 +17 @@
-            "caseExact" : false,
+            "caseExact" : true,
```

## Erratum 8417 (Verified, Technical, lines) section 8.7.2, original lines 4101-4105

```diff
--- original
+++ fixed
@@ -2,0 +3 @@
+    "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Schema"],
@@ -4,0 +6,4 @@
+    "meta" : {
+      "resourceType" : "Schema",
+      "location" : "/v2/Schemas/urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"
+    },
```

## Erratum 8417 (Verified, Technical, lines) section 8.7.2, original lines 4431-4433

```diff
--- original
+++ fixed
@@ -1,0 +2 @@
+    "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Schema"],
@@ -2,0 +4,4 @@
+    "meta" : {
+      "resourceType" : "Schema",
+      "location" : "/v2/Schemas/urn:ietf:params:scim:schemas:core:2.0:ResourceType"
+    },
```

## Erratum 7522 (Verified, Technical, lines) section 8.7.2, original lines 4513-4534

```diff
--- original
+++ fixed
@@ -4 +4 @@
-        "multiValued" : false,
+        "multiValued" : true,
```

## Erratum 8417 (Verified, Technical, lines) section 8.7.2, original lines 4562-4564

```diff
--- original
+++ fixed
@@ -1,0 +2 @@
+    "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Schema"],
@@ -2,0 +4,4 @@
+    "meta" : {
+      "resourceType" : "Schema",
+      "location" : "/v2/Schemas/urn:ietf:params:scim:schemas:core:2.0:Schema"
+    },
```

## Erratum 6727 (Verified, Editorial, lines) section 8.7.2, original lines 4612-4624

```diff
--- original
+++ fixed
@@ -5,3 +5,2 @@
-        "description" : "The schema's human-readable name.  When
-          applicable, service providers MUST specify the name,
-          e.g., 'User'.",
+        "description" : "The schema's human-readable description.  When
+          applicable, service providers MUST specify the description.",
```

## Erratum 5606 (Verified, Technical, lines) section 8.7.1, original lines 4655-4676

```diff
--- original
+++ fixed
@@ -7 +7 @@
-              'decimal', 'integer', 'dateTime', 'reference'.",
+              'decimal', 'integer', 'dateTime', 'reference', 'binary'.",
@@ -16 +16,2 @@
-              "reference"
+              "reference",
+              "binary"
```

## Erratum 8415 (Verified, Technical, lines) section 8.7.1, original lines 4880-4921

```diff
--- original
+++ fixed
@@ -26,2 +26,2 @@
-                  Valid values include 'string', 'complex', 'boolean',
-                  'decimal', 'integer', 'dateTime', 'reference'.",
+                  Valid values include 'string', 'boolean',
+                  'decimal', 'integer', 'dateTime', 'reference', 'binary'.",
@@ -35 +34,0 @@
-                  "complex",
@@ -40 +39,2 @@
-                  "reference"
+                  "reference",
+                  "binary"
```

## Erratum 5607 (Verified, Technical, lines) section 8.7.2, original lines 5063-5075

```diff
--- original
+++ fixed
@@ -4 +4 @@
-                "multiValued" : false,
+                "multiValued" : true,
```
