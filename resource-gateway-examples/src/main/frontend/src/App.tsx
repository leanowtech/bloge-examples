import AuthorCanvas from './AuthorCanvas';
import './styles.css';

/** Top-level authoring app shell. */
export default function App() {
  return (
    <div className="app">
      <header className="topbar">
        <div>
          <p className="eyebrow">BLOGE Visual Canvas</p>
          <h1>Author</h1>
        </div>
        <a className="link" href="/examples/gateway">
          Showcase →
        </a>
      </header>
      <AuthorCanvas />
    </div>
  );
}
