@PLUGIN@ REST API
==============

Initialize Repository
==

Triggers repository initialization

Request
---

```
PUT a/plugins/pull-replication/init-project/myNewProject.git
Content-Type: text/plain charset=utf-8
```

Response
---

* Project created (201)

```
HTTP/1.1 201 OK
Content-Type: text/plain charset=utf-8

Project newPro.git initialized
```

* User unauthorized (401)
* Bad request (i.e.: missing content type) (400)
* Issues with project initialization server side (500)

Apply Object
==

Triggers objects replication

Request
---

```
POST /a/projects/myProject/pull-replication~apply-object

```

Response
---

Fetch
==

Triggers ??

Request
---

```
POST /a/projects/myProject/pull-replication~fetch

```

Response
---