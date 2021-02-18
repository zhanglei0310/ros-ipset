package me.legrange.mikrotik.impl

import me.legrange.mikrotik.ApiConnectionException

class ApiTimeoutException(message: String):ApiConnectionException(message)