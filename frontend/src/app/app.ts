import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';

interface DocumentMetadata { id: string; title: string; kind: string; classification: string; chunkCount: number; }
interface Citation { chunkId: string; title: string; quote: string; score: number; }
interface AskResponse { traceId: string; answer: string; confidence: number; citations: Citation[]; guardrail: { status: string }; timing: { retrievalMs: number; generationMs: number; totalMs: number }; provider: string; question?: string; }

@Component({ selector: 'app-root', imports: [CommonModule, FormsModule], templateUrl: './app.html', styleUrl: './app.css' })
export class App implements OnInit {
  private readonly http = inject(HttpClient);
  role = 'employee'; question = ''; loading = false; statusError = false; statusText = 'Connexion…'; ingestOpen = false; ingestError = false; ingestMessage = '';
  documents: DocumentMetadata[] = []; answers: AskResponse[] = []; lastAnswer: AskResponse | null = null;
  newDocument = { id: '', title: '', kind: 'FAQ', classification: 'INTERNAL', content: '' };

  ngOnInit(): void { this.loadHealth(); this.loadDocuments(); }
  private headers() { return { 'X-Role': this.role }; }
  loadHealth(): void {
    this.http.get<{ documents: number; providers: { generation: string } }>('/api/health').subscribe({
      next: health => { this.statusText = `${health.documents} docs · JVM ready`; this.statusError = false; },
      error: () => { this.statusText = 'API indisponible'; this.statusError = true; }
    });
  }
  loadDocuments(): void { this.http.get<DocumentMetadata[]>('/api/documents', { headers: this.headers() }).subscribe({ next: docs => this.documents = docs, error: () => this.documents = [] }); }
  changeRole(role: string): void { this.role = role; this.loadDocuments(); }
  ask(question: string): void {
    const value = question.trim(); if (!value || this.loading) return; this.loading = true;
    this.http.post<AskResponse>('/api/ask', { question: value, topK: 4 }, { headers: this.headers() }).subscribe({
      next: response => { response.question = value; this.answers = [...this.answers, response]; this.lastAnswer = response; this.loading = false; },
      error: error => { this.answers = [...this.answers, { traceId: crypto.randomUUID(), question: value, answer: error.error?.detail ?? 'Erreur API', confidence: 0, citations: [], guardrail: { status: 'blocked' }, timing: { retrievalMs: 0, generationMs: 0, totalMs: 0 }, provider: 'error' }]; this.loading = false; }
    });
  }
  feedback(traceId: string, rating: string): void { this.http.post('/api/feedback', { traceId, rating, comment: '' }).subscribe(); }
  ingest(): void {
    this.ingestMessage = 'Validation et indexation…'; this.ingestError = false;
    this.http.post<{ accepted: number; warnings: string[] }>('/api/ingest', { documents: [{ ...this.newDocument, updatedAt: new Date().toISOString() }], replace: false }, { headers: { ...this.headers(), 'X-Role': 'admin' } }).subscribe({
      next: result => { if (!result.accepted) { this.ingestError = true; this.ingestMessage = result.warnings.join(' '); return; } this.ingestMessage = `${result.accepted} document indexé.`; this.ingestOpen = false; this.loadHealth(); this.loadDocuments(); },
      error: error => { this.ingestError = true; this.ingestMessage = error.error?.detail ?? 'Ingestion refusée.'; }
    });
  }
}
