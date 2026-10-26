# Simulador de Sistema Operacional

Máquina virtual em Java que simula o hardware de um computador (CPU, memória e interrupções) e um sistema operacional simples rodando sobre ele, com gerência de memória paginada, gerência de processos e escalonamento Round-Robin.

Trabalho em grupo da disciplina de **Sistemas Operacionais** da PUCRS (2025), construído a partir do código-base fornecido pelo professor.

## O que o sistema faz

- **Hardware simulado:** CPU com conjunto próprio de instruções (`LDI`, `ADD`, `JMP`, `SYSCALL`, `STOP`…), memória de palavras e tratamento de interrupções.
- **Gerência de memória:** memória dividida em frames, alocação paginada e tradução de endereços lógicos para físicos por uma MMU.
- **Gerência de processos:** criação e remoção de processos com PCB (estados `READY`, `RUNNING`, `TERMINATED`).
- **Escalonamento Round-Robin:** cada processo roda por uma fatia de instruções (quantum configurável).
- **Chamadas de sistema:** `SYSCALL` para saída de dados (`OUT`) e encerramento de processos.
- **Programas de teste:** fatorial, Fibonacci e Bubble Sort, escritos no assembly da máquina.

## Como rodar

Pré-requisito: Java 17 ou mais recente.

```bash
javac Sistema.java
java Sistema
```

O sistema abre um terminal interativo:

| Comando | O que faz |
| --- | --- |
| `new <programa>` | Carrega um programa na memória e cria o processo |
| `ps` | Lista os processos |
| `exec <pid>` | Executa um processo |
| `execAll` | Executa todos os processos prontos com Round-Robin |
| `rm <pid>` | Remove um processo e libera a memória |
| `dump <pid>` | Mostra o PCB e a memória do processo |
| `dumpM <início> <fim>` | Mostra um trecho da memória física |
| `frames` | Mostra quais frames estão livres ou ocupados |
| `quantum <n>` | Altera o tamanho da fatia de tempo |
| `traceOn` / `traceOff` | Liga ou desliga o rastreamento da execução |
| `exit` | Encerra |

Exemplo:

```
new fatorial
new fibonacci10
execAll
```

## Tecnologias

Java · Programação orientada a objetos · Conceitos de SO (paginação, MMU, PCB, escalonamento)
