package com.connectx.gateway.sms

/** Returns only the shop-configured active subscription; never chooses a device default. */
internal fun selectedActiveSubscriptionId(selectedId: Int, activeSubscriptionIds: Collection<Int>): Int? =
    selectedId.takeIf { it > 0 && activeSubscriptionIds.contains(it) }
