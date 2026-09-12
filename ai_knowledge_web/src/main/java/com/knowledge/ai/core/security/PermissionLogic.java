package com.knowledge.ai.core.security;

/**
 * {@link RequiresPerm} 多个权限码之间的关系。
 */
public enum PermissionLogic {

    /** 任意一个命中即通过。 */
    OR,

    /** 全部命中才通过。 */
    AND
}
