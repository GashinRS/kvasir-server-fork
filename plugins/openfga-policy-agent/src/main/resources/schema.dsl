model
  schema 1.1

type user

type group
  relations
    define member: [user]

type resource
  relations
    define owner: [user]
    define parent: [resource]
    define reader: [user, user:*, group#member]
    define writer: [user, user:*, group#member]
    define deleter: [user, user:*, group#member]
    define manager: [user, user:*, group#member]
    define blocked: [user, group#member]
    define can_read: (reader or owner or can_read from parent) but not blocked
    define can_write: (writer or owner or can_write from parent) but not blocked
    define can_delete: (deleter or owner or can_delete from parent) but not blocked
    define can_manage: (manager or owner or can_manage from parent) but not blocked