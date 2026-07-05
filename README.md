# RouletteChest

Paper-плагин для Minecraft 1.21.x. Превращает выбранные сундуки, эндер-сундуки и шалкеры в
рулетку: при клике по отмеченному контейнеру открывается GUI с прокруткой призов, а по
остановке игрок получает выпавшую награду — предметы, консольные команды или сразу пачку
того и другого.

- Paper API: `1.21.8-R0.1-SNAPSHOT` (`api-version: '1.21'`, работает на всех 1.21.x)
- Java 21
- Сборка через Maven

## Возможности

- Отметка любых **сундуков, ловушек, эндер-сундуков и шалкеров** (в т.ч. двойных) командой.
- Пулы призов — независимые наборы наград, каждый со своим набором настроек.
- **Взвешенный рандом**: чаще выпадает то, у чего больше `weight`.
- Раздельные `display` и `reward`: показанный предмет и выдаваемая награда могут различаться
  (например, крутится алмаз — выдаются монеты).
- **Множественная выдача**: одним призом можно выдать несколько предметов и/или запустить
  несколько команд.
- Кулдаун на пул с двумя режимами: `PLAYER` (у каждого свой) или `SERVER` (общий на блок).
- Кулдаун переживает рестарт сервера (`cooldowns.yml`).
- Пермишен обхода кулдауна.
- Concurrency: `SOLO` / `SPECTATE` / `FREE` — что делают другие игроки, когда сундук уже
  крутит кто-то.
- Кастомные сообщения игроку, глобальный broadcast победы, сообщение о занятости.
- Анимация крышки блока и правильный звук открытия/закрытия — без ванильного инвентаря.

## Установка

1. Собрать: `mvn clean package` — итог в `target/RouletteChest-1.0.0.jar`.
2. Положить jar в `plugins/` сервера, запустить.
3. Отредактировать `plugins/RouletteChest/config.yml` под свои призы, `/rchest reload`.

## Команды

Требуют `roulettechest.admin`.

| Команда | Что делает |
|---|---|
| `/rchest set <pool>` | Отметить блок, на который смотрит игрок, привязав его к пулу |
| `/rchest unset` | Снять метку с блока, на который смотрит игрок |
| `/rchest list` | Показать все отмеченные блоки и их пулы |
| `/rchest reload` | Перечитать `config.yml` |

## Права

| Пермишен | По умолчанию | Назначение |
|---|---|---|
| `roulettechest.admin` | op | Доступ к `/rchest ...` |
| `roulettechest.use` | true | Возможность запустить рулетку кликом |
| `roulettechest.cooldown.bypass` | op | Обходить кулдауны (и не ставить их себе) |

## Конфиг

`plugins/RouletteChest/config.yml`. Верхний уровень — `animation` (общие настройки прокрутки)
и `pools` (список пулов).

### animation

```yaml
animation:
  duration-ticks: 60          # длина прокрутки, 20 тиков = 1 секунда
  start-interval-ticks: 1     # начальная задержка между сдвигами (быстро)
  end-interval-ticks: 8       # конечная задержка между сдвигами (медленно)
  tick-sound: BLOCK_NOTE_BLOCK_HAT
  win-sound: ENTITY_PLAYER_LEVELUP
```

Ускорение/замедление считается автоматически по easing-формуле; количество сдвигов ленты
подсчитано так, чтобы приз-победитель точно оказался под указателем.

### pool

```yaml
pools:
  <id>:
    title: "&6&lРулетка"        # заголовок GUI, & и MiniMessage
    cooldown-seconds: 300       # 0 = кулдаун отключён
    cooldown-scope: PLAYER      # PLAYER | SERVER
    concurrency: SPECTATE       # SOLO | SPECTATE | FREE
    win-message: "..."          # игроку по завершении. {prize} {pool} {player}
    cooldown-message: "..."     # при активном кулдауне. {time} {pool} {player}
    broadcast-message: ""       # всему серверу. {prize} {pool} {player}. "" = выкл
    busy-message: "..."         # только для SOLO при занятом сундуке. {pool} {player}
    prizes: [ ... ]             # см. ниже
```

**cooldown-scope**
- `PLAYER` — у каждого игрока свой таймер, привязанный к пулу.
- `SERVER` — общий таймер, привязанный к **конкретному отмеченному блоку**. Разные сундуки
  с одним и тем же пулом кулдаунятся независимо друг от друга.

**concurrency**
- `SOLO` — пока один игрок крутит, остальные получают `busy-message`.
- `SPECTATE` — другие открывают тот же GUI в режиме просмотра, но начать не могут.
- `FREE` — каждый крутит свою рулетку параллельно.

### prizes

```yaml
prizes:
  - weight: 40                   # относительная вероятность
    display:                     # что видит игрок в GUI
      material: IRON_INGOT
      amount: 1
      name: "&fЖелезный слиток"  # опционально, лейбл в чате
      lore: []                   # опционально
    reward:                      # что реально получит
      type: ITEM
      material: IRON_INGOT
      amount: 4
```

**display** — обычный `ItemStack` с опциональными `name` и `lore` (поддерживают `&` и
MiniMessage). `name`, если задан, идёт в `{prize}` в сообщениях. Без `name` подставляется
имя материала.

**reward** может быть:

1. **Одиночная награда**:
   ```yaml
   reward:
     type: ITEM
     material: DIAMOND
     amount: 3
   ```

2. **Список разных наград** (все выдаются подряд):
   ```yaml
   reward:
     - type: ITEM
       material: DIAMOND
       amount: 3
     - type: COMMAND
       command: "eco give {player} 500"
   ```

3. **Шорткат для нескольких предметов**:
   ```yaml
   reward:
     type: ITEMS
     items:
       - { material: DIAMOND, amount: 3 }
       - { material: EMERALD, amount: 5 }
   ```

4. **Шорткат для нескольких команд**:
   ```yaml
   reward:
     type: COMMANDS
     commands:
       - "give {player} diamond 3"
       - "eco give {player} 500"
   ```

**Типы `reward.type`**:

| Тип | Поля | Что делает |
|---|---|---|
| `ITEM` | `material`, `amount`, `name`, `lore` | Кладёт стек в инвентарь, лишнее выбрасывает на пол |
| `COMMAND` | `command` | Выполняет команду от имени консоли |
| `ITEMS` | `items: [...]` | Несколько `ITEM` подряд |
| `COMMANDS` | `commands: [...]` | Несколько `COMMAND` подряд |
| `NONE` | — | Пусто (для «холостых» слотов) |

Плейсхолдер `{player}` доступен в командах и во всех сообщениях. В сообщениях также
доступны `{prize}`, `{pool}` и `{time}` (только для `cooldown-message`, а также `{player}`
в `busy-message` = имя того, кто сейчас крутит).

## Как использовать

1. Настроить пул в `config.yml`, `/rchest reload`.
2. Поставить сундук в мире.
3. Посмотреть на него, ввести `/rchest set <id-пула>`.
4. ПКМ по сундуку — крутится рулетка, звучит `open`-звук, крышка анимируется.
5. По окончании — награда, чат-сообщение, глобальный broadcast (если настроен), звук `win`.

Двойные сундуки: достаточно отметить одну половину — рулетка сработает при клике по любой.

Snake-подкоманды, sneak+ПКМ: чтобы **не** триггерить рулетку и (в теории) взаимодействовать
с блоком как обычно, зажать Shift и кликнуть — плагин пропустит событие. Полезно админам.

## Файлы данных

| Файл | Что хранит |
|---|---|
| `plugins/RouletteChest/config.yml` | Пулы и общие настройки |
| `plugins/RouletteChest/markers.yml` | Координаты отмеченных блоков и их пулы |
| `plugins/RouletteChest/cooldowns.yml` | Активные кулдауны с epoch-таймстампами |

`markers.yml` и `cooldowns.yml` пишутся автоматически, редактировать вручную не нужно.
Кулдауны истекают по реальному времени сервера — во время выключения сервера часы всё равно
идут; протухшие записи чистятся при загрузке.

## Сборка из исходников

```bash
mvn clean package
```

Требования:
- JDK 21+
- Maven 3.9+

Итоговый jar: `target/RouletteChest-1.0.0.jar`.

## Лицензия

[MIT](LICENSE).

## Структура проекта

```
src/main/java/ru/dvolk/roulettechest/
├── RouletteChestPlugin.java       — точка входа
├── command/RouletteChestCommand.java
├── data/
│   ├── PoolRegistry.java          — парсинг config.yml
│   ├── MarkerStore.java           — markers.yml
│   └── CooldownStore.java         — cooldowns.yml
├── listener/ChestInteractListener.java
├── model/
│   ├── Pool.java
│   ├── Prize.java
│   └── Reward.java                — sealed: ITEM / COMMAND / MULTI / NONE
└── roulette/
    ├── RouletteService.java       — состояние сессий, анимация, награды
    └── RouletteHolder.java        — маркер собственного GUI
```
