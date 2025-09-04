// PUCRS - Escola Politécnica - Sistemas Operacionais
// Prof. Fernando Dotti
// Código fornecido como parte da solução do projeto de Sistemas Operacionais
//
// Estrutura deste código:
//    Todo código está dentro da classe *Sistema*
//    Dentro de Sistema, encontra-se acima a definição de HW:
//           Memory,  Word, 
//           CPU tem Opcodes (codigos de operacoes suportadas na cpu),
//               e Interrupcoes possíveis, define o que executa para cada instrucao
//           VM -  a máquina virtual é uma instanciação de CPU e Memória
//    Depois as definições de SW:
//           no momento são esqueletos (so estrutura) para
//					InterruptHandling    e
//					SysCallHandling 
//    A seguir temos utilitários para usar o sistema
//           carga, início de execução e dump de memória
//    Por último os programas existentes, que podem ser copiados em memória.
//           Isto representa programas armazenados.
//    Veja o main.  Ele instancia o Sistema com os elementos mencionados acima.
//           em seguida solicita a execução de algum programa com  loadAndExec

import java.util.*;

public class Sistema {
	

	// -------------------------------------------------------------------------------------------------------
	// --------------------- H A R D W A R E - definicoes de HW
	// ----------------------------------------------

	// -------------------------------------------------------------------------------------------------------
	// --------------------- M E M O R I A - definicoes de palavra de memoria,
	// memória ----------------------

	public class Memory {
		public Word[] pos; // pos[i] é a posição i da memória. cada posição é uma palavra.

		public Memory(int size) {
			pos = new Word[size];
			for (int i = 0; i < pos.length; i++) {
				pos[i] = new Word(Opcode.___, -1, -1, -1);
			}
			; // cada posicao da memoria inicializada
		}
	}

	public class Word {    // cada posicao da memoria tem uma instrucao (ou um dado)
		public Opcode opc; //
		public int ra;     // indice do primeiro registrador da operacao (Rs ou Rd cfe opcode na tabela)
		public int rb;     // indice do segundo registrador da operacao (Rc ou Rs cfe operacao)
		public int p;      // parametro para instrucao (k ou A cfe operacao), ou o dado, se opcode = DADO

		public Word(Opcode _opc, int _ra, int _rb, int _p) { // vide definição da VM - colunas vermelhas da tabela
			opc = _opc;
			ra = _ra;
			rb = _rb;
			p  = _p;
		}
	}

	// -------------------------------------------------------------------------------------------------------
	// --------------------- C P U - definicoes da CPU
	// -----------------------------------------------------

	public enum Opcode {
		DATA, ___,              // se memoria nesta posicao tem um dado, usa DATA, se nao usada ee NULO ___
		JMP, JMPI, JMPIG, JMPIL, JMPIE, // desvios
		JMPIM, JMPIGM, JMPILM, JMPIEM,
		JMPIGK, JMPILK, JMPIEK, JMPIGT,
		ADDI, SUBI, ADD, SUB, MULT,    // matematicos
		LDI, LDD, STD, LDX, STX, MOVE, // movimentacao
		SYSCALL, STOP                  // chamada de sistema e parada
	}

	public enum Interrupts {
		noInterrupt,
		intEnderecoInvalido,
		intInstrucaoInvalida,
		intOverflow,
		intClock   // <-- NECESSÁRIO PARA O runSlice()
	}
	

	public class CPU {
		private int maxInt; // valores maximo e minimo para inteiros nesta cpu
		private int minInt;
	
		// CONTEXTO da CPU
		private int pc;         // program counter
		private Word ir;        // instruction register
		private int[] reg;      // registradores da CPU
		private Interrupts irpt; // interrupcao sinalizada durante execucao
	
		private Word[] m;       // memoria fisica
	
		private InterruptHandling ih;    // rotinas de tratamento de int
		private SysCallHandling sysCall; // rotinas de tratamento de syscall
	
		private boolean cpuStop; // parar CPU (stop ou erro)
		private boolean debug;   // trace
		private Utilities u;     // dump
	
		private PCB pcb;         // processo atual (para tradução MMU)
		private MMU mmu;         // mmu para traduzir endereços
		private int tamPg;       // opcional, cache
	
		// controle de fatia (parte C)
		private int instrCountPerSlice = 0;

		public int getPc() { return pc; }
		public int[] getRegs() { return reg; }
	
		public CPU(Memory _mem, boolean _debug) {
			maxInt = 32767;
			minInt = -32767;
			m = _mem.pos;
			reg = new int[10];   // regs 8 e 9 usados apenas p/ IO
			debug = _debug;
		}
	
		public void setAddressOfHandlers(InterruptHandling _ih, SysCallHandling _sysCall) {
			ih = _ih;
			sysCall = _sysCall;
		}
	
		public void setUtilities(Utilities _u) { u = _u; }
	
		public void setMMU(MMU _mmu, int _tamPg) { this.mmu = _mmu; this.tamPg = _tamPg; }
	
		// (modo compatível com as partes A/B)
		public void setContext(int _pc, PCB _pcb) {
			pc = _pc;
			pcb = _pcb;
			irpt = Interrupts.noInterrupt;
			// zera regs para execucao "inteira" (sem preempcao)
			for (int i = 0; i < 10; i++) reg[i] = 0;
		}
	
		public void setDebug(boolean on) { this.debug = on; }
	
		// ------------------ helpers de contexto (parte C) ------------------
		private void loadContextFromPCB(PCB p) {
			this.pc = p.pc;
			for (int i = 0; i < 10; i++) this.reg[i] = p.regs[i];
			this.irpt = Interrupts.noInterrupt;
		}
		private void saveContextToPCB(PCB p) {
			p.pc = this.pc;
			for (int i = 0; i < 10; i++) p.regs[i] = this.reg[i];
		}
	
		// ------------------ validacoes basicas ------------------
		private boolean legal(int e) { // (aqui seria end. fisico; com MMU quase nao usamos)
			if (e >= 0 && e < m.length) return true;
			irpt = Interrupts.intEnderecoInvalido;
			return false;
		}
	
		private boolean testOverflow(int v) {
			if ((v < minInt) || (v > maxInt)) {
				irpt = Interrupts.intOverflow;
				return false;
			}
			return true;
		}
	
		// ================== run() (compatibilidade: executa ate STOP/erro) ==================
		public void run() {
			cpuStop = false;
			while (!cpuStop) {
				// FETCH
				try {
					ir = mmu.read(pcb, pc); // PC logico -> MMU traduz
					if (debug) {
						System.out.print("                                              regs: ");
						for (int i = 0; i < 10; i++) System.out.print(" r[" + i + "]:" + reg[i]);
						System.out.println();
						System.out.print("                      pc: " + pc + "       exec: ");
						u.dump(ir);
					}
				} catch (Exception e) {
					irpt = Interrupts.intEnderecoInvalido;
				}
	
				// EXEC
				if (irpt == Interrupts.noInterrupt) {
					switch (ir.opc) {
						// --- MOVIMENTAÇÃO / MEMÓRIA ---
						case LDI: reg[ir.ra] = ir.p; pc++; break;
	
						case LDD: // Rd <- [A] (A logico)
							try { reg[ir.ra] = mmu.read(pcb, ir.p).p; pc++; }
							catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						case LDX: // RD <- [RS] (end logico em reg[rb])
							try { reg[ir.ra] = mmu.read(pcb, reg[ir.rb]).p; pc++; }
							catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						case STD: // [A] <- Rs (A logico)
							try {
								mmu.writeData(pcb, ir.p, reg[ir.ra]);
								pc++;
								if (debug) { System.out.print("                                  "); u.dump(ir.p, ir.p+1); }
							} catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						case STX: // [Rd] <- Rs (end logico em reg[ra])
							try { mmu.writeData(pcb, reg[ir.ra], reg[ir.rb]); pc++; }
							catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						case MOVE: reg[ir.ra] = reg[ir.rb]; pc++; break;
	
						// --- ARITMÉTICAS ---
						case ADD:  reg[ir.ra] = reg[ir.ra] + reg[ir.rb]; testOverflow(reg[ir.ra]); pc++; break;
						case ADDI: reg[ir.ra] = reg[ir.ra] + ir.p;        testOverflow(reg[ir.ra]); pc++; break;
						case SUB:  reg[ir.ra] = reg[ir.ra] - reg[ir.rb]; testOverflow(reg[ir.ra]); pc++; break;
						case SUBI: reg[ir.ra] = reg[ir.ra] - ir.p;        testOverflow(reg[ir.ra]); pc++; break;
						case MULT: reg[ir.ra] = reg[ir.ra] * reg[ir.rb]; testOverflow(reg[ir.ra]); pc++; break;
	
						// --- DESVIOS ---
						case JMP:    pc = ir.p; break;
	
						case JMPI:   pc = reg[ir.ra]; break;
	
						case JMPIM:  // PC <- [A]
							try { pc = mmu.read(pcb, ir.p).p; }
							catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						case JMPIG:  if (reg[ir.rb] > 0) pc = reg[ir.ra]; else pc++; break;
						case JMPIL:  if (reg[ir.rb] < 0) pc = reg[ir.ra]; else pc++; break;
						case JMPIE:  if (reg[ir.rb] == 0) pc = reg[ir.ra]; else pc++; break;
	
						case JMPIGK: if (reg[ir.rb] > 0) pc = ir.p; else pc++; break;
						case JMPILK: if (reg[ir.rb] < 0) pc = ir.p; else pc++; break;
						case JMPIEK: if (reg[ir.rb] == 0) pc = ir.p; else pc++; break;
						case JMPIGT: if (reg[ir.ra] > reg[ir.rb]) pc = ir.p; else pc++; break;
	
						case JMPIGM:
							try { if (reg[ir.rb] > 0) pc = mmu.read(pcb, ir.p).p; else pc++; }
							catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						case JMPILM:
							try { if (reg[ir.rb] < 0) pc = mmu.read(pcb, ir.p).p; else pc++; }
							catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						case JMPIEM:
							try { if (reg[ir.rb] == 0) pc = mmu.read(pcb, ir.p).p; else pc++; }
							catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						// --- DADOS ONDE NÃO DEVERIA HAVER INSTRUÇÃO ---
						case DATA:
							irpt = Interrupts.intInstrucaoInvalida;
							break;
	
						// --- SYSCALL / STOP ---
						case SYSCALL:
							sysCall.handle();
							pc++;
							break;
	
						case STOP:
							sysCall.stop();
							cpuStop = true;
							break;
	
						default:
							irpt = Interrupts.intInstrucaoInvalida;
							break;
					}
				}
	
				// VERIFICA INTERRUPÇÃO
				if (irpt != Interrupts.noInterrupt) {
					ih.handle(irpt);
					cpuStop = true;
				}
			}
		}
	
		// ================== runSlice(delta) (parte C: preempcao por tempo) ==================
		// Executa no maximo 'delta' instrucoes para o PCB 'p'.
		// Retorna a causa (intClock, overflow, endereco invalido, etc.) ou noInterrupt se parou por STOP.
		public Interrupts runSlice(int delta, PCB p) {
			this.pcb = p;
			instrCountPerSlice = 0;
			cpuStop = false;
	
			loadContextFromPCB(p); // restaura contexto salvo
	
			while (!cpuStop) {
				// FETCH
				try {
					ir = mmu.read(pcb, pc); // PC logico -> MMU traduz
					if (debug) {
						System.out.print("                                              regs: ");
						for (int i = 0; i < 10; i++) System.out.print(" r[" + i + "]:" + reg[i]);
						System.out.println();
						System.out.print("                      pc: " + pc + "       exec: ");
						u.dump(ir);
					}
				} catch (Exception e) {
					irpt = Interrupts.intEnderecoInvalido;
				}
	
				// EXEC
				if (irpt == Interrupts.noInterrupt) {
					switch (ir.opc) {
						// --- MOVIMENTAÇÃO / MEMÓRIA ---
						case LDI: reg[ir.ra] = ir.p; pc++; break;
	
						case LDD:
							try { reg[ir.ra] = mmu.read(pcb, ir.p).p; pc++; }
							catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						case LDX:
							try { reg[ir.ra] = mmu.read(pcb, reg[ir.rb]).p; pc++; }
							catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						case STD:
							try {
								mmu.writeData(pcb, ir.p, reg[ir.ra]);
								pc++;
								if (debug) { System.out.print("                                  "); u.dump(ir.p, ir.p+1); }
							} catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						case STX:
							try { mmu.writeData(pcb, reg[ir.ra], reg[ir.rb]); pc++; }
							catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						case MOVE: reg[ir.ra] = reg[ir.rb]; pc++; break;
	
						// --- ARITMÉTICAS ---
						case ADD:  reg[ir.ra] = reg[ir.ra] + reg[ir.rb]; testOverflow(reg[ir.ra]); pc++; break;
						case ADDI: reg[ir.ra] = reg[ir.ra] + ir.p;        testOverflow(reg[ir.ra]); pc++; break;
						case SUB:  reg[ir.ra] = reg[ir.ra] - reg[ir.rb]; testOverflow(reg[ir.ra]); pc++; break;
						case SUBI: reg[ir.ra] = reg[ir.ra] - ir.p;        testOverflow(reg[ir.ra]); pc++; break;
						case MULT: reg[ir.ra] = reg[ir.ra] * reg[ir.rb]; testOverflow(reg[ir.ra]); pc++; break;
	
						// --- DESVIOS ---
						case JMP:    pc = ir.p; break;
	
						case JMPI:   pc = reg[ir.ra]; break;
	
						case JMPIM:
							try { pc = mmu.read(pcb, ir.p).p; }
							catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						case JMPIG:  if (reg[ir.rb] > 0) pc = reg[ir.ra]; else pc++; break;
						case JMPIL:  if (reg[ir.rb] < 0) pc = reg[ir.ra]; else pc++; break;
						case JMPIE:  if (reg[ir.rb] == 0) pc = reg[ir.ra]; else pc++; break;
	
						case JMPIGK: if (reg[ir.rb] > 0) pc = ir.p; else pc++; break;
						case JMPILK: if (reg[ir.rb] < 0) pc = ir.p; else pc++; break;
						case JMPIEK: if (reg[ir.rb] == 0) pc = ir.p; else pc++; break;
						case JMPIGT: if (reg[ir.ra] > reg[ir.rb]) pc = ir.p; else pc++; break;
	
						case JMPIGM:
							try { if (reg[ir.rb] > 0) pc = mmu.read(pcb, ir.p).p; else pc++; }
							catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						case JMPILM:
							try { if (reg[ir.rb] < 0) pc = mmu.read(pcb, ir.p).p; else pc++; }
							catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						case JMPIEM:
							try { if (reg[ir.rb] == 0) pc = mmu.read(pcb, ir.p).p; else pc++; }
							catch (Exception e) { irpt = Interrupts.intEnderecoInvalido; }
							break;
	
						// --- DADOS ONDE NÃO DEVERIA HAVER INSTRUÇÃO ---
						case DATA:
							irpt = Interrupts.intInstrucaoInvalida;
							break;
	
						// --- SYSCALL / STOP ---
						case SYSCALL:
							sysCall.handle();
							pc++;
							break;
	
						case STOP:
							sysCall.stop();   // marca TERMINATED no PCB (feito no SysCallHandling)
							cpuStop = true;   // encerra esta fatia (processo terminou)
							break;
	
						default:
							irpt = Interrupts.intInstrucaoInvalida;
							break;
					}
				}
	
				// contou uma instrução desta fatia?
				instrCountPerSlice++;
	
				// Se houve interrupção (erro), encerra a fatia:
				if (irpt != Interrupts.noInterrupt) break;
	
				// Se atingiu o tempo e não terminou por STOP, gera intClock:
				if (!cpuStop && instrCountPerSlice >= delta) {
					irpt = Interrupts.intClock;
					break;
				}
			}
	
			// salva contexto antes de sair
			saveContextToPCB(p);
	
			// Nao chama ih.handle() aqui; quem chamou runSlice decide o que fazer
			return irpt;
		}
	}
	
	// ------------------ C P U - fim
	// -----------------------------------------------------------------------
	// ------------------------------------------------------------------------------------------------------

	// ------------------- HW - constituido de CPU e MEMORIA
	// -----------------------------------------------
	public class HW {
		public Memory mem;
		public CPU cpu;

		public HW(int tamMem) {
			mem = new Memory(tamMem);
			cpu = new CPU(mem, true); // true liga debug
		}
	}
	// -------------------------------------------------------------------------------------------------------

	// --------------------H A R D W A R E - fim
	// -------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------

	// ///////////////////////////////////////////////////////////////////////////////////////////////////////

	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// ------------------- SW - inicio - Sistema Operacional
	// -------------------------------------------------

	// ------------------- I N T E R R U P C O E S - rotinas de tratamento
	// ----------------------------------
	public class InterruptHandling {
		private HW hw; // referencia ao hw se tiver que setar algo

		public InterruptHandling(HW _hw) {
			hw = _hw;
		}

		public void handle(Interrupts irpt) {
			// apenas avisa - todas interrupcoes neste momento finalizam o programa
			System.out.println("                                               Interrupcao " + irpt + "   pc: " + hw.cpu.getPc());
		}
	}

	// ------------------- C H A M A D A S D E S I S T E M A - rotinas de tratamento
	// ----------------------
	public class SysCallHandling {
		private HW hw; // referencia ao hw se tiver que setar algo

		public SysCallHandling(HW _hw) {
			hw = _hw;
		}

		public void stop() {
			System.out.println("                                               SYSCALL STOP");
			if (so.gp != null && so.gp.running != null) {
				so.gp.running.estado = EstadoProc.TERMINATED;
			}
		}

		public void handle() {
			int a = hw.cpu.getRegs()[8];
			int b = hw.cpu.getRegs()[9];
			System.out.println("SYSCALL pars:  " + a + " / " + b);
		
			if (a == 2) { // OUT
				try {
					Word w = so.mmu.read(so.gp.running, b); // endereço lógico
					System.out.println("OUT:   " + w.p);
				} catch (Exception e) {
					System.out.println("OUT: endereco invalido");
				}
			} else if (a == 1) {
				// IN (se for implementar)
			} else {
				System.out.println("  PARAMETRO INVALIDO");
			}
		}
		

		
	}

	// ------------------ U T I L I T A R I O S D O S I S T E M A
	// -----------------------------------------
	// ------------------ load é invocado a partir de requisição do usuário

	// carga na memória
	public class Utilities {
		private HW hw;

		public Utilities(HW _hw) {
			hw = _hw;
		}

		private void loadProgram(Word[] p) {
			Word[] m = hw.mem.pos; // m[] é o array de posições memória do hw
			for (int i = 0; i < p.length; i++) {
				m[i].opc = p[i].opc;
				m[i].ra = p[i].ra;
				m[i].rb = p[i].rb;
				m[i].p = p[i].p;
			}
		}

		// dump da memória
		public void dump(Word w) { // funcoes de DUMP nao existem em hardware - colocadas aqui para facilidade
			System.out.print("[ ");
			System.out.print(w.opc);
			System.out.print(", ");
			System.out.print(w.ra);
			System.out.print(", ");
			System.out.print(w.rb);
			System.out.print(", ");
			System.out.print(w.p);
			System.out.println("  ] ");
		}

		public void dump(int ini, int fim) {
			Word[] m = hw.mem.pos;
			for (int i = ini; i < fim; i++) {
				System.out.print("(F) " + i + ":  ");
				dump(m[i]);
			}
		}
			

		// Em Utilities, adicione:
		private void loadProgramPaged(Program prog, PCB pcb, MMU mmu) {
			Word[] image = prog.image;
			for (int i = 0; i < image.length; i++) {
				try {
					mmu.writeWord(pcb, i, image[i]); // escreve palavra i no endereço lógico i
				} catch (Exception e) {
					throw new RuntimeException("Falha na carga paginada: " + e.getMessage());
				}
			}
		}
	}

	public class SO {
		public InterruptHandling ih;
		public SysCallHandling sc;
		public Utilities utils;
	
		public MMU mmu;           // já existia/ajustado
		public GerenteProcessos gp; // <-- adiciona isto
	
		public SO(HW hw) {
			ih = new InterruptHandling(hw);
			sc = new SysCallHandling(hw);
			hw.cpu.setAddressOfHandlers(ih, sc);
			utils = new Utilities(hw);
		}
	}
	
	
	// -------------------------------------------------------------------------------------------------------
	// ------------------- S I S T E M A
	// --------------------------------------------------------------------

	public HW hw;
	public SO so;
	public Programs progs;

	public GerenteMemoria gm;
	public MMU mmu;
	public PCB pcbAtual; // processo atual


	public Sistema(int tamMem, int tamPg) {
		hw = new HW(tamMem);             // se quiser desativar trace por padrão, veja passo 6
		so = new SO(hw);
		hw.cpu.setUtilities(so.utils);
	
		gm  = new GerenteMemoria(tamMem, tamPg);
		mmu = new MMU(gm, hw.mem);
		progs = new Programs();
	
		hw.cpu.setMMU(mmu, tamPg);
	
		// disponibiliza para SysCall e GP
		so.mmu = mmu;
		so.gp  = new GerenteProcessos(hw, so, gm, mmu);
	}
	
	
	public void run() {
		Scanner scn = new Scanner(System.in);
		System.out.println("SO pronto. Comandos: new <prog> | rm <pid> | ps | dump <pid> | dumpM <i> <f> | exec <pid> | traceOn | traceOff | exit");
		while (true) {
			System.out.print("> ");
			if (!scn.hasNextLine()) break;
			String line = scn.nextLine().trim();
			if (line.isEmpty()) continue;
			String[] tok = line.split("\\s+");
			String cmd = tok[0].toLowerCase();
	
			try {
				switch (cmd) {
					case "new":
						if (tok.length < 2) { System.out.println("uso: new <nomePrograma>"); break; }
						so.gp.criaProcesso(tok[1]);
						break;
					
					case "map":
						if (tok.length < 2) { System.out.println("uso: map <pid>"); break; }
						so.gp.map(Integer.parseInt(tok[1]));
						break;
					
					case "frames":
						so.gp.frames();
						break;
					
					case "rm":
						if (tok.length < 2) { System.out.println("uso: rm <pid>"); break; }
						so.gp.desalocaProcesso(Integer.parseInt(tok[1]));
						break;
	
					case "ps":
						so.gp.ps();
						break;
	
					case "dump":
						if (tok.length < 2) { System.out.println("uso: dump <pid>"); break; }
						so.gp.dumpPCB(Integer.parseInt(tok[1]));
						break;
	
					case "dumpm":
						if (tok.length < 3) { System.out.println("uso: dumpM <ini> <fim>"); break; }
						so.gp.dumpMem(Integer.parseInt(tok[1]), Integer.parseInt(tok[2]));
						break;
	
					case "exec":
						if (tok.length < 2) { System.out.println("uso: exec <pid>"); break; }
						so.gp.exec(Integer.parseInt(tok[1]));
						break;
	
					case "traceon":
						so.gp.setTrace(true);  System.out.println("trace ON");  break;
	
					case "traceoff":
						so.gp.setTrace(false); System.out.println("trace OFF"); break;
	
					case "exit":
						System.out.println("bye.");
						return;
	
					default:
						System.out.println("comando desconhecido");
				}
			} catch (Exception e) {
				System.out.println("erro: " + e.getMessage());
			}
		}
	}
	
	
	
	// ------------------- S I S T E M A - fim
	// --------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------

	// -------------------------------------------------------------------------------------------------------
	// ------------------- instancia e testa sistema
	public static void main(String args[]) {
		Sistema s = new Sistema(1024, 8);
		s.run();
	}

	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// --------------- P R O G R A M A S - não fazem parte do sistema
	// esta classe representa programas armazenados (como se estivessem em disco)
	// que podem ser carregados para a memória (load faz isto)

	public class Program {
		public String name;
		public Word[] image;

		public Program(String n, Word[] i) {
			name = n;
			image = i;
		}
	}

	public class Programs {

		public Word[] retrieveProgram(String pname) {
			for (Program p : progs) {
				if (p != null && p.name.equals(pname)) return p.image;
			}
			return null;
		}
		

		public Program[] progs = {
				new Program("fatorial",
						new Word[] {
								// este fatorial so aceita valores positivos. nao pode ser zero
								// linha coment
								new Word(Opcode.LDI, 0, -1, 7), // 0 r0 é valor a calcular fatorial
								new Word(Opcode.LDI, 1, -1, 1), // 1 r1 é 1 para multiplicar (por r0)
								new Word(Opcode.LDI, 6, -1, 1), // 2 r6 é 1 o decremento
								new Word(Opcode.LDI, 7, -1, 8), // 3 r7 tem posicao 8 para fim do programa
								new Word(Opcode.JMPIE, 7, 0, 0), // 4 se r0=0 pula para r7(=8)
								new Word(Opcode.MULT, 1, 0, -1), // 5 r1 = r1 * r0 (r1 acumula o produto por cada termo)
								new Word(Opcode.SUB, 0, 6, -1), // 6 r0 = r0 - r6 (r6=1) decrementa r0 para proximo
																// termo
								new Word(Opcode.JMP, -1, -1, 4), // 7 vai p posicao 4
								new Word(Opcode.STD, 1, -1, 10), // 8 coloca valor de r1 na posição 10
								new Word(Opcode.STOP, -1, -1, -1), // 9 stop
								new Word(Opcode.DATA, -1, -1, -1) // 10 ao final o valor está na posição 10 da memória
						}),

				new Program("fatorialV2",
						new Word[] {
								new Word(Opcode.LDI, 0, -1, 5), // numero para colocar na memoria, ou pode ser lido
								new Word(Opcode.STD, 0, -1, 19),
								new Word(Opcode.LDD, 0, -1, 19),
								new Word(Opcode.LDI, 1, -1, -1),
								new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
								new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
								new Word(Opcode.LDI, 1, -1, 1),
								new Word(Opcode.LDI, 6, -1, 1),
								new Word(Opcode.LDI, 7, -1, 13),
								new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula para STD (Stop-1)
								new Word(Opcode.MULT, 1, 0, -1),
								new Word(Opcode.SUB, 0, 6, -1),
								new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
								new Word(Opcode.STD, 1, -1, 18),
								new Word(Opcode.LDI, 8, -1, 2), // escrita
								new Word(Opcode.LDI, 9, -1, 18), // endereco com valor a escrever
								new Word(Opcode.SYSCALL, -1, -1, -1),
								new Word(Opcode.STOP, -1, -1, -1), // POS 17
								new Word(Opcode.DATA, -1, -1, -1), // POS 18
								new Word(Opcode.DATA, -1, -1, -1) } // POS 19
				),

				new Program("progMinimo",
						new Word[] {
								new Word(Opcode.LDI, 0, -1, 999),
								new Word(Opcode.STD, 0, -1, 8),
								new Word(Opcode.STD, 0, -1, 9),
								new Word(Opcode.STD, 0, -1, 10),
								new Word(Opcode.STD, 0, -1, 11),
								new Word(Opcode.STD, 0, -1, 12),
								new Word(Opcode.STOP, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // 7
								new Word(Opcode.DATA, -1, -1, -1), // 8
								new Word(Opcode.DATA, -1, -1, -1), // 9
								new Word(Opcode.DATA, -1, -1, -1), // 10
								new Word(Opcode.DATA, -1, -1, -1), // 11
								new Word(Opcode.DATA, -1, -1, -1), // 12
								new Word(Opcode.DATA, -1, -1, -1) // 13
						}),

				new Program("fibonacci10",
						new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 20),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 21),
								new Word(Opcode.LDI, 0, -1, 22),
								new Word(Opcode.LDI, 6, -1, 6),
								new Word(Opcode.LDI, 7, -1, 30),
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 1, -1),
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.ADD, 1, 2, -1),
								new Word(Opcode.ADD, 2, 3, -1),
								new Word(Opcode.STX, 0, 2, -1),
								new Word(Opcode.ADDI, 0, -1, 1),
								new Word(Opcode.SUB, 7, 0, -1),
								new Word(Opcode.JMPIG, 6, 7, -1),
								new Word(Opcode.STOP, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // POS 20
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1) // ate aqui - serie de fibonacci ficara armazenada
						}),

				new Program("fibonacci10v2",
						new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 20),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 21),
								new Word(Opcode.LDI, 0, -1, 22),
								new Word(Opcode.LDI, 6, -1, 6),
								new Word(Opcode.LDI, 7, -1, 31),
								new Word(Opcode.MOVE, 3, 1, -1),
								new Word(Opcode.MOVE, 1, 2, -1),
								new Word(Opcode.ADD, 2, 3, -1),
								new Word(Opcode.STX, 0, 2, -1),
								new Word(Opcode.ADDI, 0, -1, 1),
								new Word(Opcode.SUB, 7, 0, -1),
								new Word(Opcode.JMPIG, 6, 7, -1),
								new Word(Opcode.STOP, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // POS 20
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1) // ate aqui - serie de fibonacci ficara armazenada
						}),
				new Program("fibonacciREAD",
						new Word[] {
								// mesmo que prog exemplo, so que usa r0 no lugar de r8
								new Word(Opcode.LDI, 8, -1, 1), // leitura
								new Word(Opcode.LDI, 9, -1, 55), // endereco a guardar o tamanho da serie de fib a gerar
																	// - pode ser de 1 a 20
								new Word(Opcode.SYSCALL, -1, -1, -1),
								new Word(Opcode.LDD, 7, -1, 55),
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 7, -1),
								new Word(Opcode.LDI, 4, -1, 36), // posicao para qual ira pular (stop) *
								new Word(Opcode.LDI, 1, -1, -1), // caso negativo
								new Word(Opcode.STD, 1, -1, 41),
								new Word(Opcode.JMPIL, 4, 7, -1), // pula pra stop caso negativo *
								new Word(Opcode.JMPIE, 4, 7, -1), // pula pra stop caso 0
								new Word(Opcode.ADDI, 7, -1, 41), // fibonacci + posição do stop
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.STD, 1, -1, 41), // 25 posicao de memoria onde inicia a serie de
																	// fibonacci gerada
								new Word(Opcode.SUBI, 3, -1, 1), // se 1 pula pro stop
								new Word(Opcode.JMPIE, 4, 3, -1),
								new Word(Opcode.ADDI, 3, -1, 1),
								new Word(Opcode.LDI, 2, -1, 1),
								new Word(Opcode.STD, 2, -1, 42),
								new Word(Opcode.SUBI, 3, -1, 2), // se 2 pula pro stop
								new Word(Opcode.JMPIE, 4, 3, -1),
								new Word(Opcode.LDI, 0, -1, 43),
								new Word(Opcode.LDI, 6, -1, 25), // salva posição de retorno do loop
								new Word(Opcode.LDI, 5, -1, 0), // salva tamanho
								new Word(Opcode.ADD, 5, 7, -1),
								new Word(Opcode.LDI, 7, -1, 0), // zera (inicio do loop)
								new Word(Opcode.ADD, 7, 5, -1), // recarrega tamanho
								new Word(Opcode.LDI, 3, -1, 0),
								new Word(Opcode.ADD, 3, 1, -1),
								new Word(Opcode.LDI, 1, -1, 0),
								new Word(Opcode.ADD, 1, 2, -1),
								new Word(Opcode.ADD, 2, 3, -1),
								new Word(Opcode.STX, 0, 2, -1),
								new Word(Opcode.ADDI, 0, -1, 1),
								new Word(Opcode.SUB, 7, 0, -1),
								new Word(Opcode.JMPIG, 6, 7, -1), // volta para o inicio do loop
								new Word(Opcode.STOP, -1, -1, -1), // POS 36
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1), // POS 41
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1)
						}),
						new Program("PB",
						new Word[] {
						  new Word(Opcode.LDI, 0, -1, 7),   // numero para colocar na memoria
						  new Word(Opcode.STD, 0, -1, 16),  // guarda ENTRADA em 16   (era 19)
						  new Word(Opcode.LDD, 0, -1, 16),  // carrega ENTRADA        (era 19)
						  new Word(Opcode.LDI, 1, -1, -1),
						  new Word(Opcode.LDI, 2, -1, 13),  // SALVAR POS STOP
						  new Word(Opcode.JMPIL, 2, 0, -1),
						  new Word(Opcode.LDI, 1, -1, 1),
						  new Word(Opcode.LDI, 6, -1, 1),
						  new Word(Opcode.LDI, 7, -1, 13),
						  new Word(Opcode.JMPIE, 7, 0, 0),
						  new Word(Opcode.MULT, 1, 0, -1),
						  new Word(Opcode.SUB, 0, 6, -1),
						  new Word(Opcode.JMP, -1, -1, 9),
						  new Word(Opcode.STD, 1, -1, 15),  // escreve SAÍDA em 15    (era 18)
						  new Word(Opcode.STOP, -1, -1, -1),
						  new Word(Opcode.DATA, -1, -1, -1), // 15: SAÍDA
						  new Word(Opcode.DATA, -1, -1, -1)  // 16: ENTRADA
						}
					  ),					  
				new Program("PC",
						new Word[] {
								// Para um N definido (10 por exemplo)
								// o programa ordena um vetor de N números em alguma posição de memória;
								// ordena usando bubble sort
								// loop ate que não swap nada
								// passando pelos N valores
								// faz swap de vizinhos se da esquerda maior que da direita
								new Word(Opcode.LDI, 7, -1, 5), // TAMANHO DO BUBBLE SORT (N)
								new Word(Opcode.LDI, 6, -1, 5), // aux N
								new Word(Opcode.LDI, 5, -1, 46), // LOCAL DA MEMORIA
								new Word(Opcode.LDI, 4, -1, 47), // aux local memoria
								new Word(Opcode.LDI, 0, -1, 4), // colocando valores na memoria
								new Word(Opcode.STD, 0, -1, 46),
								new Word(Opcode.LDI, 0, -1, 3),
								new Word(Opcode.STD, 0, -1, 47),
								new Word(Opcode.LDI, 0, -1, 5),
								new Word(Opcode.STD, 0, -1, 48),
								new Word(Opcode.LDI, 0, -1, 1),
								new Word(Opcode.STD, 0, -1, 49),
								new Word(Opcode.LDI, 0, -1, 2),
								new Word(Opcode.STD, 0, -1, 50), // colocando valores na memoria até aqui - POS 13
								new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 1
								new Word(Opcode.STD, 3, -1, 99),
								new Word(Opcode.LDI, 3, -1, 22), // Posicao para pulo CHAVE 2
								new Word(Opcode.STD, 3, -1, 98),
								new Word(Opcode.LDI, 3, -1, 38), // Posicao para pulo CHAVE 3
								new Word(Opcode.STD, 3, -1, 97),
								new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 4 (não usada)
								new Word(Opcode.STD, 3, -1, 96),
								new Word(Opcode.LDI, 6, -1, 0), // r6 = r7 - 1 POS 22
								new Word(Opcode.ADD, 6, 7, -1),
								new Word(Opcode.SUBI, 6, -1, 1), // ate aqui
								new Word(Opcode.JMPIEM, -1, 6, 97), // CHAVE 3 para pular quando r7 for 1 e r6 0 para
																	// interomper o loop de vez do programa
								new Word(Opcode.LDX, 0, 5, -1), // r0 e ra pegando valores das posições da memoria POS
																// 26
								new Word(Opcode.LDX, 1, 4, -1),
								new Word(Opcode.LDI, 2, -1, 0),
								new Word(Opcode.ADD, 2, 0, -1),
								new Word(Opcode.SUB, 2, 1, -1),
								new Word(Opcode.ADDI, 4, -1, 1),
								new Word(Opcode.SUBI, 6, -1, 1),
								new Word(Opcode.JMPILM, -1, 2, 99), // LOOP chave 1 caso neg procura prox
								new Word(Opcode.STX, 5, 1, -1),
								new Word(Opcode.SUBI, 4, -1, 1),
								new Word(Opcode.STX, 4, 0, -1),
								new Word(Opcode.ADDI, 4, -1, 1),
								new Word(Opcode.JMPIGM, -1, 6, 99), // LOOP chave 1 POS 38
								new Word(Opcode.ADDI, 5, -1, 1),
								new Word(Opcode.SUBI, 7, -1, 1),
								new Word(Opcode.LDI, 4, -1, 0), // r4 = r5 + 1 POS 41
								new Word(Opcode.ADD, 4, 5, -1),
								new Word(Opcode.ADDI, 4, -1, 1), // ate aqui
								new Word(Opcode.JMPIGM, -1, 7, 98), // LOOP chave 2
								new Word(Opcode.STOP, -1, -1, -1), // POS 45
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1),
								new Word(Opcode.DATA, -1, -1, -1)
						})
		};
	}
	public class GerenteProcessos {
		// --- dependências do SO/HW ---
		private final HW hw;
		private final SO so;
		private final GerenteMemoria gm;
		private final MMU mmu;
	
		// --- estruturas do GP ---
		private int nextPid = 1;
		public  PCB running = null;                 // visível ao SysCall (OUT usa so.gp.running)
		private final Map<Integer, PCB> tabela = new HashMap<>();
		private final Deque<PCB> ready = new ArrayDeque<>();
	
		// --- parâmetros do escalonador ---
		private int quantum = 8;                    // nº de instruções por fatia (ajuste à vontade)
	
		public GerenteProcessos(HW hw, SO so, GerenteMemoria gm, MMU mmu) {
			this.hw = hw; this.so = so; this.gm = gm; this.mmu = mmu;
		}
	
		// ------------------------------------------------------------
		// utilitários de inspeção (opcionais, mas úteis p/ teste)
		// ------------------------------------------------------------
		public void frames() { gm.printFrames(); }
	
		public void map(int pid) {
			PCB pcb = tabela.get(pid);
			if (pcb == null) { System.out.println("PID inexistente"); return; }
			int tamPg = gm.tamPg();
			System.out.println("Mapeamento LOG -> FIS (pid="+pid+", tamPg="+tamPg+"):");
			for (int log = 0; log < pcb.tamLogico; log++) {
				try {
					int fis   = mmu.traduz(pcb, log);
					int page  = log / tamPg;
					int off   = log % tamPg;
					int frame = pcb.tabelaPaginas[page];
					System.out.printf("L=%-3d  (pag=%-2d off=%-2d)  ->  FIS=%-4d  (frame=%-3d)%n",
									  log, page, off, fis, frame);
				} catch (Exception e) {
					System.out.printf("L=%-3d  <endereco invalido>%n", log);
				}
			}
		}
	
		// ------------------------------------------------------------
		// criação / remoção / listagem
		// ------------------------------------------------------------
		public Integer criaProcesso(String nomeProg) {
			Word[] image = progs.retrieveProgram(nomeProg);
			if (image == null) { System.out.println("Programa nao encontrado: " + nomeProg); return null; }
			int tam = image.length;
	
			int[] tp = gm.aloca(tam);
			if (tp == null) { System.out.println("Sem memoria para " + nomeProg); return null; }
	
			int pid = nextPid++;
			PCB pcb = new PCB(pid, nomeProg, tp, tam);
	
			// carga paginada (lógico -> físico via MMU)
			for (int i = 0; i < tam; i++) {
				try { mmu.writeWord(pcb, i, image[i]); }
				catch (Exception e) { throw new RuntimeException("Falha carga: "+e.getMessage()); }
			}
	
			tabela.put(pid, pcb);
			ready.addLast(pcb);
			pcb.estado = EstadoProc.READY;
			System.out.println("Processo criado: pid=" + pid + " nome=" + nomeProg);
			return pid;
		}
	
		public boolean desalocaProcesso(int pid) {
			PCB pcb = tabela.get(pid);
			if (pcb == null) { System.out.println("PID inexistente: " + pid); return false; }
			if (running == pcb) { running = null; } // se estivesse rodando, derruba
			ready.remove(pcb);
			gm.desaloca(pcb.tabelaPaginas);
			tabela.remove(pid);
			System.out.println("Processo removido: " + pid);
			return true;
		}
	
		public void ps() {
			System.out.println("PID  ESTADO       NOME        PC  TAM  PAGs");
			for (PCB pcb : tabela.values()) {
				System.out.printf("%-4d %-12s %-10s %-3d %-4d %-4d%n",
								  pcb.pid, pcb.estado, pcb.nome, pcb.pc, pcb.tamLogico, pcb.tabelaPaginas.length);
			}
			if (running != null) {
				System.out.println("RUNNING: pid=" + running.pid + " ("+running.nome+")");
			}
		}
	
		public void dumpPCB(int pid) {
			PCB pcb = tabela.get(pid);
			if (pcb == null) { System.out.println("PID inexistente"); return; }
	
			System.out.println("=== PCB pid="+pcb.pid+" nome="+pcb.nome+" estado="+pcb.estado+" pc="+pcb.pc);
			System.out.print("Tabela de páginas (page -> frame): ");
			for (int i = 0; i < pcb.tabelaPaginas.length; i++) {
				System.out.print(i + "->" + pcb.tabelaPaginas[i] + (i+1<pcb.tabelaPaginas.length ? ", " : ""));
			}
			System.out.println();
	
			// registradores salvos (Parte C)
			System.out.print("Regs: ");
			for (int i = 0; i < pcb.regs.length; i++) {
				System.out.print("r"+i+"="+pcb.regs[i] + (i+1<pcb.regs.length ? " " : ""));
			}
			System.out.println();
	
			// dump lógico do programa
			for (int i = 0; i < pcb.tamLogico; i++) {
				try {
					Word w = mmu.read(pcb, i);
					System.out.printf("%d:  [ %s, %d, %d, %d ]%n", i, w.opc, w.ra, w.rb, w.p);
				} catch (Exception e) { System.out.println(i + ": <invalid>"); }
			}
		}
	
		// memória física (dumpM)
		public void dumpMem(int ini, int fim) { so.utils.dump(ini, fim); }
	
		// trace
		public void setTrace(boolean on) { hw.cpu.setDebug(on); }
	
		// quantum
		public void setQuantum(int q) { this.quantum = Math.max(1, q); }
	
		// ------------------------------------------------------------
		// Execução (Parte C): Round-Robin com fatias de tempo
		// ------------------------------------------------------------
	
		// compatibilidade com o comando "exec <pid>":
		// - coloca o pid escolhido na frente da fila e roda RR.
		public boolean exec(int pid) {
			PCB alvo = tabela.get(pid);
			if (alvo == null) { System.out.println("PID inexistente"); return false; }
			if (!ready.remove(alvo) && alvo.estado != EstadoProc.READY) {
				// se não estava na fila READY, (re)insere
			}
			// garante que está em READY e vai ser o próximo
			alvo.estado = EstadoProc.READY;
			ready.addFirst(alvo);
	
			// executa RR até esvaziar a fila
			scheduleRR();
			return true;
		}
	
		// loop principal do escalonador RR
		private void scheduleRR() {
			while (!ready.isEmpty()) {
				PCB pcb = ready.pollFirst();
				running = pcb;
				pcb.estado = EstadoProc.RUNNING;
	
				// disponibiliza referências p/ SysCall (OUT) e CPU
				so.mmu = mmu;
				so.gp  = this;
	
				// executa uma fatia
				Interrupts motivo = hw.cpu.runSlice(quantum, pcb);
	
				// decide pós-execução
				switch (motivo) {
					case noInterrupt:  // terminou por STOP
						finalizarProcessoOk(pcb);
						break;
	
					case intClock:     // tempo esgotado -> volta para READY
						pcb.estado = EstadoProc.READY;
						ready.addLast(pcb);
						break;
	
					case intEnderecoInvalido:
					case intInstrucaoInvalida:
					case intOverflow:
					default:           // qualquer erro -> mata processo
						System.out.println("Processo " + pcb.pid + " encerrado por " + motivo);
						matarProcesso(pcb);
						break;
				}
	
				running = null;
			}
		}
	
		private void finalizarProcessoOk(PCB pcb) {
			pcb.estado = EstadoProc.TERMINATED;
			// desaloca memória e remove da tabela
			gm.desaloca(pcb.tabelaPaginas);
			tabela.remove(pcb.pid);
			System.out.println("Processo " + pcb.pid + " terminou (STOP). Memória liberada.");
		}
	
		private void matarProcesso(PCB pcb) {
			pcb.estado = EstadoProc.TERMINATED;
			gm.desaloca(pcb.tabelaPaginas);
			tabela.remove(pcb.pid);
		}
	}	
	

	// Dentro de Sistema (nível similar a HW/SO), crie:
	public class GerenteMemoria {
		private final int tamMem;
		private final int tamPg;
		private final int numFrames;
		private final boolean[] livre; // true = livre, false = ocupado

		public GerenteMemoria(int tamMem, int tamPg) {
			this.tamMem = tamMem;
			this.tamPg = tamPg;
			this.numFrames = tamMem / tamPg;
			this.livre = new boolean[numFrames];
			Arrays.fill(livre, true);
		}

		public void printFrames() {
			System.out.println("Frames: livre=true, ocupado=false");
			for (int i = 0; i < livre.length; i++) {
				System.out.printf("frame %-3d : %s%n", i, livre[i]);
			}
		}		

		// Solicita nº de palavras e devolve tabela de páginas (page->frame)
		public int[] aloca(int nroPalavras) {
			int nPaginas = (int)Math.ceil(nroPalavras / (double)tamPg);
			int[] tabela = new int[nPaginas];

			// varre frames livres
			int achados = 0;
			for (int f = 0; f < numFrames && achados < nPaginas; f++) {
				if (livre[f]) {
					tabela[achados++] = f;
				}
			}
			if (achados < nPaginas) return null; // sem espaço

			// marca ocupados
			for (int i = 0; i < nPaginas; i++) livre[tabela[i]] = false;
			return tabela;
		}

		public void desaloca(int[] tabelaPaginas) {
			if (tabelaPaginas == null) return;
			for (int f : tabelaPaginas) {
				if (f >= 0 && f < numFrames) livre[f] = true;
			}
		}

		public int tamPg() { return tamPg; }
	}

	public enum EstadoProc { READY, RUNNING, TERMINATED }

	public class PCB {
		public int pid;
		public String nome;
		public int[] tabelaPaginas;
		public int tamLogico;
		public int pc;                 // PC lógico
		public EstadoProc estado;
	
		// --- NOVO: snapshot de registradores para preempção ---
		public int[] regs = new int[10];
	
		public PCB(int pid, String nome, int[] tp, int tamLogico) {
			this.pid = pid;
			this.nome = nome;
			this.tabelaPaginas = tp;
			this.tamLogico = tamLogico;
			this.pc = 0;
			this.estado = EstadoProc.READY;
			// opcional: zera os registradores
			for (int i = 0; i < regs.length; i++) regs[i] = 0;
		}
	}
	

	
		// Ainda em Sistema:
	public class MMU {
		private final GerenteMemoria gm;
		private final Memory mem; // memória física (Word[])
		public MMU(GerenteMemoria gm, Memory mem) { this.gm = gm; this.mem = mem; }

		// traduz lógico->físico usando a tabela do processo
		public int traduz(PCB pcb, int endLogico) throws Exception {
			if (endLogico < 0 || endLogico >= pcb.tamLogico)
				throw new Exception("Endereco logico invalido");
			int tamPg = gm.tamPg();
			int page = endLogico / tamPg;
			int off  = endLogico % tamPg;
			if (page < 0 || page >= pcb.tabelaPaginas.length)
				throw new Exception("Pagina invalida");
			int frame = pcb.tabelaPaginas[page];
			if (frame < 0) throw new Exception("Frame invalido");
			return frame * tamPg + off;
		}

		public Word read(PCB pcb, int endLogico) throws Exception {
			int fis = traduz(pcb, endLogico);
			return mem.pos[fis];
		}

		public void writeData(PCB pcb, int endLogico, int data) throws Exception {
			int fis = traduz(pcb, endLogico);
			mem.pos[fis].opc = Opcode.DATA;
			mem.pos[fis].p = data;
		}

		public void writeWord(PCB pcb, int endLogico, Word w) throws Exception {
			int fis = traduz(pcb, endLogico);
			mem.pos[fis].opc = w.opc;
			mem.pos[fis].ra  = w.ra;
			mem.pos[fis].rb  = w.rb;
			mem.pos[fis].p   = w.p;
		}
	}

	



}