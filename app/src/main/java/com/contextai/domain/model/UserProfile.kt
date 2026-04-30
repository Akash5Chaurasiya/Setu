package com.contextai.domain.model

data class UserProfile(
    val name: String = "",
    val currentRole: String = "",
    val skills: String = "",
    val experience: String = ""
) {
    fun isComplete(): Boolean = name.isNotBlank() && currentRole.isNotBlank()

    fun toPromptContext(): String = buildString {
        if (name.isNotBlank()) appendLine("User name: $name")
        if (currentRole.isNotBlank()) appendLine("Current role: $currentRole")
        if (skills.isNotBlank()) appendLine("Skills: $skills")
        if (experience.isNotBlank()) appendLine("Experience: $experience")
    }
}
