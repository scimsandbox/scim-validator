# Errata applied to rfc7644.txt

Statuses applied: Verified. Applied edits: 5 from 5 errata. Skipped: 0.


## Erratum 8096 (Verified, Technical, lines) section 3.5.2.1, original lines 2100-2114

```diff
--- original
+++ fixed
@@ -1 +1 @@
-   {
+  {
@@ -13,0 +14 @@
+        }
```

## Erratum 7916 (Verified, Technical, lines) section 3.7.3, original lines 3210-3223

```diff
--- original
+++ fixed
@@ -3 +3 @@
-   "status": "201"
+ The following is an example of a status in a failed operation.
@@ -5,9 +5,5 @@
-   The following is an example of a status in a failed operation.
-
-  "status": "400",
-  "response":{
-       "schemas": ["urn:ietf:params:scim:api:messages:2.0:Error"],
-       "scimType":"invalidSyntax"
-       "detail":
-  "Request is unparsable, syntactically incorrect, or violates schema.",
-       "status":"400"
+  {
+     "status": "400",
+     "schemas": ["urn:ietf:params:scim:api:messages:2.0:Error"],
+     "scimType":"invalidSyntax",
+     "detail":"Request is unparsable, syntactically incorrect, or violates schema.",
```

## Erratum 7898 (Verified, Editorial, lines) section 3.12, original lines 3937-3946

```diff
--- original
+++ fixed
@@ -7 +7 @@
-     "scimType":"mutability"
+     "scimType":"mutability",
```

## Erratum 8365 (Verified, Technical, words) section 4, original lines 4112-4112

```diff
--- original
+++ fixed
@@ -1 +1 @@
-      Groups).  Each resource type defines the endpoints, the core
+      Groups).  Each resource type defines the endpoint, the core
```

## Erratum 6893 (Verified, Editorial, lines) section 7.5.2, original lines 4451-4459

```diff
--- original
+++ fixed
@@ -8 +8 @@
-    "status": "404"
+    "status": "403"
```
