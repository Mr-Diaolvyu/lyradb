<template>
  <div class="db-icon-wrapper" :style="{ width: size + 'px', height: size + 'px' }">
    <!-- MySQL -->
    <svg v-if="dbType === 'MYSQL'" viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient id="mysql-grad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#00A4E4" />
          <stop offset="100%" stop-color="#00758A" />
        </linearGradient>
      </defs>
      <rect width="256" height="256" rx="48" fill="url(#mysql-grad)" />
      <path d="M64 180 L64 76 L96 76 L96 152 L140 152 L140 180 Z" fill="#fff" />
      <path d="M160 76 L192 76 L192 180 L160 180 Z M160 76 L192 76 L176 56 Z" fill="#fff" />
      <circle cx="176" cy="64" r="8" fill="#fff" />
    </svg>

    <!-- PostgreSQL -->
    <svg v-else-if="dbType === 'POSTGRESQL'" viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient id="pg-grad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#336791" />
          <stop offset="100%" stop-color="#26415C" />
        </linearGradient>
      </defs>
      <rect width="256" height="256" rx="48" fill="url(#pg-grad)" />
      <path d="M128 60 C90 60 60 85 60 120 C60 155 85 180 120 180 C130 180 140 178 145 175 L155 185 L152 168 C170 158 180 140 180 120 C180 85 165 60 128 60 Z" fill="#fff" opacity="0.95" />
      <text x="128" y="135" text-anchor="middle" fill="#336791" font-size="48" font-weight="bold" font-family="sans-serif">PG</text>
    </svg>

    <!-- Oracle -->
    <svg v-else-if="dbType === 'ORACLE'" viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient id="oracle-grad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#C74634" />
          <stop offset="100%" stop-color="#8B2D20" />
        </linearGradient>
      </defs>
      <rect width="256" height="256" rx="48" fill="url(#oracle-grad)" />
      <ellipse cx="128" cy="128" rx="70" ry="40" fill="none" stroke="#fff" stroke-width="12" />
      <ellipse cx="128" cy="128" rx="40" ry="20" fill="#fff" />
    </svg>

    <!-- SQL Server -->
    <svg v-else-if="dbType === 'MSSQL'" viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient id="mssql-grad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#CC2927" />
          <stop offset="100%" stop-color="#8E1A18" />
        </linearGradient>
      </defs>
      <rect width="256" height="256" rx="48" fill="url(#mssql-grad)" />
      <path d="M80 90 C80 90 120 75 160 80 C180 82 195 90 195 90 L175 100 C175 100 190 110 190 125 C190 145 165 160 128 160 C90 160 66 145 66 125 C66 110 80 100 80 100 Z" fill="#fff" />
      <text x="128" y="135" text-anchor="middle" fill="#CC2927" font-size="36" font-weight="bold" font-family="sans-serif">SQL</text>
    </svg>

    <!-- SQLite -->
    <svg v-else-if="dbType === 'SQLITE'" viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient id="sqlite-grad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#003B57" />
          <stop offset="100%" stop-color="#001F30" />
        </linearGradient>
      </defs>
      <rect width="256" height="256" rx="48" fill="url(#sqlite-grad)" />
      <path d="M70 80 L70 180 L90 180 L90 120 L130 180 L150 180 L150 80 L130 80 L130 140 L90 80 Z" fill="#fff" />
      <path d="M170 80 L170 180 L186 180 L186 80 Z" fill="#7BB5A0" />
    </svg>

    <!-- ClickHouse -->
    <svg v-else-if="dbType === 'CLICKHOUSE'" viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient id="ch-grad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#FFCC00" />
          <stop offset="100%" stop-color="#FF8A00" />
        </linearGradient>
      </defs>
      <rect width="256" height="256" rx="48" fill="url(#ch-grad)" />
      <rect x="64" y="72" width="24" height="112" rx="4" fill="#333" />
      <rect x="100" y="100" width="24" height="84" rx="4" fill="#333" />
      <rect x="136" y="88" width="24" height="96" rx="4" fill="#333" />
      <rect x="172" y="120" width="24" height="64" rx="4" fill="#333" />
    </svg>

    <!-- MaxCompute -->
    <svg v-else-if="dbType === 'MAXCOMPUTE'" viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient id="mc-grad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#FF6A00" />
          <stop offset="100%" stop-color="#CC4400" />
        </linearGradient>
      </defs>
      <rect width="256" height="256" rx="48" fill="url(#mc-grad)" />
      <path d="M128 56 L128 200 M56 128 L200 128 M77 77 L179 179 M179 77 L77 179" stroke="#fff" stroke-width="6" opacity="0.3" />
      <circle cx="128" cy="128" r="20" fill="#fff" />
      <circle cx="128" cy="76" r="14" fill="#fff" opacity="0.9" />
      <circle cx="180" cy="128" r="14" fill="#fff" opacity="0.9" />
      <circle cx="128" cy="180" r="14" fill="#fff" opacity="0.9" />
      <circle cx="76" cy="128" r="14" fill="#fff" opacity="0.9" />
    </svg>

    <!-- MongoDB -->
    <svg v-else-if="dbType === 'MONGODB'" viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient id="mongo-grad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#4DB33D" />
          <stop offset="100%" stop-color="#2D6A1F" />
        </linearGradient>
      </defs>
      <rect width="256" height="256" rx="48" fill="url(#mongo-grad)" />
      <path d="M128 50 C128 50 95 100 95 150 C95 175 110 195 128 205 C146 195 161 175 161 150 C161 100 128 50 128 50 Z" fill="#fff" />
      <path d="M128 205 L128 120 L132 120 L132 205 Z" fill="#2D6A1F" />
      <path d="M128 120 C128 120 140 100 145 90 L140 95 L135 88 Z" fill="#2D6A1F" />
    </svg>

    <!-- Redis -->
    <svg v-else-if="dbType === 'REDIS'" viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <linearGradient id="redis-grad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#DC382D" />
          <stop offset="100%" stop-color="#A02316" />
        </linearGradient>
      </defs>
      <rect width="256" height="256" rx="48" fill="url(#redis-grad)" />
      <path d="M60 100 L196 100 L196 130 L60 130 Z" fill="#fff" opacity="0.95" />
      <path d="M60 140 L196 140 L196 170 L60 170 Z" fill="#fff" opacity="0.8" />
      <circle cx="80" cy="115" r="6" fill="#DC382D" />
      <circle cx="80" cy="155" r="6" fill="#DC382D" />
      <text x="128" y="120" text-anchor="middle" fill="#DC382D" font-size="20" font-weight="bold" font-family="sans-serif">R</text>
      <text x="128" y="160" text-anchor="middle" fill="#DC382D" font-size="20" font-weight="bold" font-family="sans-serif">edis</text>
    </svg>

    <!-- Fallback -->
    <svg v-else viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg">
      <rect width="256" height="256" rx="48" fill="#6B7280" />
      <path d="M64 88 L128 64 L192 88 L192 168 L128 192 L64 168 Z" fill="none" stroke="#fff" stroke-width="8" />
      <path d="M128 64 L128 192 M64 88 L128 112 L192 88 M64 168 L128 144 L192 168" fill="none" stroke="#fff" stroke-width="4" opacity="0.6" />
    </svg>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  dbType: string
  size?: number
}>()
</script>

<style scoped>
.db-icon-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.db-icon-wrapper svg {
  width: 100%;
  height: 100%;
  display: block;
}
</style>
