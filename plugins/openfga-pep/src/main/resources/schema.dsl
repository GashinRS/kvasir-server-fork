model
  schema 1.1

type user

type resource
  relations
    define owner: [user, user:* with external_access]
    define parent: [resource]
    define reader: [user, user:*, user:* with external_access]
    define writer: [user, user:*, user:* with external_access]
    define deleter: [user, user:*, user:* with external_access]
    define blocked: [user, user:* with external_access]
    define can_read: (reader or owner or can_read from parent) but not blocked
    define can_write: (writer or owner or can_write from parent) but not blocked
    define can_delete: (deleter or owner or can_delete from parent) but not blocked

condition external_access(access_type: list<string>, check_type: string) {
   check_type in access_type
}