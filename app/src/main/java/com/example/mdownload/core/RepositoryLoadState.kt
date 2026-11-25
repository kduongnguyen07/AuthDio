package com.example.mdownload.core

/**
 * Deterministic startup hydration state for asynchronously loaded local JSON repositories.
 */
enum class RepositoryLoadState {
    LOADING,
    READY,
    FAILED
}
