/*
 * This file is generated by jOOQ.
 */
package advanced.jooq.model;

import advanced.jooq.model.tables.NewUser;
import advanced.jooq.model.tables.User;
import advanced.jooq.model.tables.records.NewUserRecord;
import advanced.jooq.model.tables.records.UserRecord;
import org.jooq.TableField;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.Internal;

/**
 * A class modelling foreign key relationships and constraints of tables in
 * the default schema.
 */
@SuppressWarnings({"all", "unchecked", "rawtypes"})
public class Keys {

	// -------------------------------------------------------------------------
	// UNIQUE and PRIMARY KEY definitions
	// -------------------------------------------------------------------------

	public static final UniqueKey<NewUserRecord> CONSTRAINT_5 = Internal.createUniqueKey(NewUser.NEW_USER, DSL.name("CONSTRAINT_5"), new TableField[]{NewUser.NEW_USER.ID}, true);
	public static final UniqueKey<UserRecord> CONSTRAINT_3 = Internal.createUniqueKey(User.USER, DSL.name("CONSTRAINT_3"), new TableField[]{User.USER.ID}, true);
}
